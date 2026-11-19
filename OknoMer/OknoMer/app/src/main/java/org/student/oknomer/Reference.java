package org.student.oknomer;

enum Reference {
    A4("a4", "Лист А4", "А4", 210, 297, 120),
    CARD("id1", "Банковская карта", "Карта", 53.98, 85.60, 32);

    final String id, label, shortLabel;
    final double shortSide, longSide;
    final int minPixels;

    Reference(String id,String label,String shortLabel,double shortSide,double longSide,int minPixels){
        this.id=id;
        this.label=label;
        this.shortLabel=shortLabel;
        this.shortSide=shortSide;
        this.longSide=longSide;
        this.minPixels=minPixels;
    }
    double width(boolean landscape){return landscape?longSide:shortSide;}
    double height(boolean landscape){return landscape?shortSide:longSide;}
    String dimensions(boolean landscape){
        String format=this==CARD?"%.2f × %.2f мм":"%.0f × %.0f мм";
        return String.format(java.util.Locale.forLanguageTag("ru"),format,width(landscape),height(landscape));
    }
    static Reference fromId(String id){return CARD.id.equals(id)?CARD:A4;}
}
