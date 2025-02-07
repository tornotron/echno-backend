package org.tornotron.echno_backend.common.embed;

import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.util.Objects;

@Embeddable
public class GeoLocation {

    @NotNull
    @DecimalMin(value = "-90.0")
    @DecimalMax(value = "90.0")
    private Double latitude;

    @NotNull
    @DecimalMin(value = "-180.0")
    @DecimalMax(value = "180.0")
    private Double longitude;

    public GeoLocation() {
    }

    public GeoLocation(Double latitude, Double longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public Double getLatitude() {
        return latitude;
    }

    public void setLatitude(Double latitude) {
        this.latitude = latitude;
    }

    public Double getLongitude() {
        return longitude;
    }

    public void setLongitude(Double longitude) {
        this.longitude = longitude;
    }

    @Override
    public String toString() {
        return "GeoLocation{" +
                "latitude=" + latitude +
                ", longitude=" + longitude +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        GeoLocation that = (GeoLocation) o;
        return Objects.equals(latitude, that.latitude) && Objects.equals(longitude, that.longitude);
    }

    @Override
    public int hashCode() {
        return Objects.hash(latitude, longitude);
    }
}
