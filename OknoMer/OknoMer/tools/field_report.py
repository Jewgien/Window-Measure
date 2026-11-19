"""Prepare and score field measurements exported by OknoMer. Python 3.9+, stdlib."""
import argparse
import csv
import io
import json
import math
from collections import defaultdict
from pathlib import Path
from statistics import mean
from zipfile import ZipFile

FIELDS = [
    "id", "объект", "название", "фото", "эталон", "ракурс", "условия", "разметка",
    "статус", "рулетка_ширина", "рулетка_высота", "до_ширина", "до_высота",
    "после_ширина", "после_высота", "причина_отказа",
]


def prepare(archives, destination):
    records = {}
    for archive in archives:
        with ZipFile(archive) as z:
            csv_text = z.read("measurements.csv").decode("utf-8-sig")
            names = {r["Фото"]: r["Объект"] for r in csv.DictReader(io.StringIO(csv_text), delimiter=";")}
            for raw in json.loads(z.read("measurements.json")):
                p = raw["payload"]["result"]
                photo = "photos/" + raw["photo"]
                z.getinfo(photo)
                values = {
                    "id": raw["id"], "объект": names[raw["photo"]], "название": raw["name"],
                    "фото": f"{archive.name}/{photo}", "статус": "ок",
                    "эталон": "Карта" if p.get("reference_type") == "id1" else "А4",
                    "до_ширина": p["baseline_width_mm"], "до_высота": p["baseline_height_mm"],
                    "после_ширина": p["width_mm"], "после_высота": p["height_mm"],
                }
                previous = records.get(raw["id"])
                if previous is not None:
                    for key in ("до_ширина", "до_высота", "после_ширина", "после_высота"):
                        if previous[key] != values[key]:
                            raise ValueError(f"Разные результаты для одного замера {raw['id']}")
                    continue
                records[raw["id"]] = values
    if not records:
        raise ValueError("В архивах нет сохранённых замеров.")
    with destination.open("x", encoding="utf-8-sig", newline="") as f:
        w = csv.DictWriter(f, FIELDS, delimiter=";")
        w.writeheader()
        w.writerows(records.values())
    print(f"Подготовлено строк: {len(records)}. Заполните данные натурной проверки в {destination}.")


def positive(row, field, line):
    try:
        value = float(row[field].strip().replace(",", "."))
        if value <= 0 or not math.isfinite(value):
            raise ValueError
        return value
    except (ValueError, AttributeError):
        raise ValueError(f"Строка {line}: требуется положительное число в поле «{field}».") from None


def escape(value):
    return str(value).replace("|", "\\|").replace("\n", " ")


def report(source, output):
    samples, details, seen = [], [], set()
    with source.open(encoding="utf-8-sig", newline="") as f:
        reader = csv.DictReader(f, delimiter=";")
        missing = set(FIELDS) - set(reader.fieldnames or [])
        if missing:
            raise ValueError("Отсутствуют колонки: " + ", ".join(sorted(missing)))
        for line, row in enumerate(reader, 2):
            if not any((v or "").strip() for v in row.values() if isinstance(v, str)):
                continue
            for field in ("id", "объект", "ракурс", "эталон"):
                if not (row[field] or "").strip():
                    raise ValueError(f"Строка {line}: заполните «{field}».")
            if row["id"] in seen:
                raise ValueError(f"Строка {line}: повтор id {row['id']}.")
            seen.add(row["id"])
            status = row["статус"].strip().casefold()
            if status not in ("ок", "отказ"):
                raise ValueError(f"Строка {line}: статус должен быть «ок» или «отказ».")
            samples.append(row)
            if status == "отказ":
                if not row["причина_отказа"].strip():
                    raise ValueError(f"Строка {line}: укажите причину отказа.")
                continue
            scored = dict(row)
            for axis in ("ширина", "высота"):
                truth = positive(row, "рулетка_" + axis, line)
                for mode in ("до", "после"):
                    err = abs(positive(row, mode + "_" + axis, line) - truth)
                    scored[f"{mode}_{axis}_мм"] = err
                    scored[f"{mode}_{axis}_проц"] = err / truth * 100
            details.append(scored)
    if not samples:
        raise ValueError("В таблице нет испытаний.")
    metrics = [f"{mode}_{axis}_{unit}" for mode in ("до", "после")
               for axis in ("ширина", "высота") for unit in ("мм", "проц")]
    output.mkdir(parents=True, exist_ok=True)
    indexed = {d["id"]: d for d in details}
    with (output / "errors.csv").open("w", encoding="utf-8-sig", newline="") as f:
        w = csv.DictWriter(f, FIELDS + metrics, delimiter=";")
        w.writeheader()
        for row in samples:
            measured = indexed.get(row["id"])
            extra = {k: round(measured[k], 3) for k in metrics} if measured else {}
            w.writerow({**row, **extra})
    lines = ["# Натурные измерения", "", f"Попыток — {len(samples)}. Сохранено — {len(details)}. "
             f"Отказов — {len(samples)-len(details)}.", "",
             "Погрешности рассчитаны относительно рулетки для одних и тех же принятых кадров. "
             "«До» соответствует линейному масштабу, «после» — коррекции перспективы.", "",
             "| Группа | Принято / попыток | MAE ширины до → после, мм | MAE высоты до → после, мм | "
             "Средняя ошибка после, ширина / высота, % |", "|---|---:|---:|---:|---:|"]
    groups = defaultdict(list)
    groups["Все"].extend(samples)
    for row in samples:
        for field in ("ракурс", "эталон", "условия"):
            if row[field].strip():
                groups[field.capitalize() + " · " + row[field].strip()].append(row)
    for label, rows in groups.items():
        valid = [indexed[r["id"]] for r in rows if r["id"] in indexed]
        cells = ["—"] * 3
        if valid:
            def avg(key):
                return mean(r[key] for r in valid)
            cells = [f"{avg('до_'+axis+'_мм'):.1f} → {avg('после_'+axis+'_мм'):.1f}" for axis in ("ширина", "высота")]
            cells.append(f"{avg('после_ширина_проц'):.2f} / {avg('после_высота_проц'):.2f}")
        lines.append("| " + " | ".join([escape(label), f"{len(valid)} / {len(rows)}", *cells]) + " |")
    if details:
        worst = max(details, key=lambda r: max(r["после_ширина_проц"], r["после_высота_проц"]))
        lines.extend(["", f"Наибольшее отклонение среди принятых кадров у «{escape(worst['название'])}» "
                      f"({escape(worst['id'])}, {escape(worst['ракурс'])}). Ширина — "
                      f"{worst['после_ширина_проц']:.2f} %, высота — {worst['после_высота_проц']:.2f} %."])
    (output / "report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(output / "report.md")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    a = commands.add_parser("prepare", help="создать CSV из одного или нескольких ZIP приложения")
    a.add_argument("archives", type=Path, nargs="+")
    a.add_argument("--out", type=Path, default=Path("trials.csv"))
    b = commands.add_parser("report", help="проверить таблицу и посчитать ошибки")
    b.add_argument("table", type=Path)
    b.add_argument("--out", type=Path, default=Path("field-results"))
    args = parser.parse_args()
    try:
        if args.command == "prepare":
            prepare(args.archives, args.out)
        else:
            report(args.table, args.out)
    except (OSError, KeyError, ValueError) as error:
        parser.exit(1, str(error) + "\n")


if __name__ == "__main__":
    main()
