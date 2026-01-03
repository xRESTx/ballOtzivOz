package org.example.model;

import java.util.Objects;

public class ProductRow {
    private final String name;
    private final String url;
    private final int price;
    private final int points;
    private final double percent;

    public ProductRow(String name, String url, int price, int points, double percent) {
        this.name = name != null ? name : "";
        this.url = Objects.requireNonNull(url);
        this.price = price;
        this.points = points;
        this.percent = percent;
    }

    public String getName() {
        return name;
    }

    public String getUrl() {
        return url;
    }

    public int getPrice() {
        return price;
    }

    public int getPoints() {
        return points;
    }

    public double getPercent() {
        return percent;
    }

    public String toTsvLine() {
        return name + "\t" + url + "\t" + price + "\t" + points + "\t" + String.format("%.2f", percent);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ProductRow that = (ProductRow) o;
        return price == that.price && points == that.points 
            && Double.compare(that.percent, percent) == 0 
            && url.equals(that.url);
    }

    @Override
    public int hashCode() {
        return Objects.hash(url, price, points, percent);
    }

    @Override
    public String toString() {
        return "ProductRow{name='" + name + "', url='" + url + "', price=" + price 
            + ", points=" + points + ", percent=" + String.format("%.2f", percent) + "}";
    }
}

