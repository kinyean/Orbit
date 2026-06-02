package space.orbit.backend.io;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One satellite record from CelesTrak GP / OMM JSON (the format the reachable
 * mirrors serve). Mean Keplerian elements, NOT TLE line strings — we build an
 * Orekit {@code TLE} from these fields in {@link space.orbit.backend.prop.TleFactory}.
 *
 * <p>Units as delivered by CelesTrak: {@code meanMotion} in rev/day; angles in
 * degrees; {@code bstar} in 1/earth-radii. Conversion to Orekit's SI/radian
 * conventions happens in the factory.
 *
 * <p>Unknown JSON fields are ignored so the parser tolerates schema drift
 * between mirrors.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GpRecord(
        @JsonProperty("OBJECT_NAME") String objectName,
        @JsonProperty("OBJECT_ID") String objectId,
        @JsonProperty("EPOCH") String epoch,
        @JsonProperty("MEAN_MOTION") double meanMotion,
        @JsonProperty("ECCENTRICITY") double eccentricity,
        @JsonProperty("INCLINATION") double inclination,
        @JsonProperty("RA_OF_ASC_NODE") double raan,
        @JsonProperty("ARG_OF_PERICENTER") double argPericenter,
        @JsonProperty("MEAN_ANOMALY") double meanAnomaly,
        @JsonProperty("NORAD_CAT_ID") int noradId,
        @JsonProperty("ELEMENT_SET_NO") int elementSetNo,
        @JsonProperty("REV_AT_EPOCH") int revAtEpoch,
        @JsonProperty("BSTAR") double bstar,
        @JsonProperty("CLASSIFICATION_TYPE") String classificationType,
        @JsonProperty("EPHEMERIS_TYPE") int ephemerisType) {
}
