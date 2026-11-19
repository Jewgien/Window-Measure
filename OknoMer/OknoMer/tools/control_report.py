"""Extract reproducible tables from the Android test log."""
import argparse
import csv
from pathlib import Path
import re


def table(path, fields, rows):
    with path.open("w", newline="", encoding="utf-8-sig") as out:
        writer = csv.writer(out, delimiter=";")
        writer.writerow(fields)
        writer.writerows(rows)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", type=Path)
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    data = args.log.read_text(encoding="utf-8")
    if data.splitlines().count("ALL PASSED") != 2:
        parser.error("Нужен полный журнал двух успешных фаз Android-проверки.")
    baselines = {name: (w, h) for name, w, h in re.findall(r"^CASE (\S+) baseline=([.\d]+)x([.\d]+)", data, re.M)}
    rows = []
    for scene, cw, ch, aw, ah in re.findall(r"^(\S+) exact=([.\d]+)x([.\d]+) auto=([.\d]+)x([.\d]+)", data, re.M):
        rows.append([scene, 1200, 1500, *baselines[scene], cw, ch, aw, ah,
                     round(abs(float(aw)/1200-1)*100, 2), round(abs(float(ah)/1500-1)*100, 2)])
    noise = re.findall(r"^NOISE (\S+) n=(\d+) old_valid=(\d+) old_median_pct=([.\d]+) old_p95_pct=([.\d]+) "
                       r"new_valid=(\d+) new_median_pct=([.\d]+) new_p95_pct=([.\d]+)", data, re.M)
    if len(rows) != 7 or len(noise) != 4:
        parser.error("Журнал не содержит всех контрольных сцен.")
    args.directory.mkdir(parents=True, exist_ok=True)
    table(args.directory / "control-scenes.csv",
          ["scene", "known_w_mm", "known_h_mm", "baseline_w_mm", "baseline_h_mm",
           "corrected_w_mm", "corrected_h_mm", "auto_w_mm", "auto_h_mm", "auto_w_error_pct", "auto_h_error_pct"], rows)
    table(args.directory / "card-noise.csv",
          ["scene", "trials", "old_valid", "old_median_pct", "old_p95_pct", "new_valid", "new_median_pct", "new_p95_pct"], noise)


if __name__ == "__main__":
    main()
