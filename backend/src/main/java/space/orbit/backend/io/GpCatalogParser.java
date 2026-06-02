package space.orbit.backend.io;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Parses a CelesTrak GP / OMM JSON array (from a bundled snapshot or a mirror)
 * into {@link GpRecord}s. Pure data transform — no Orekit involvement.
 */
@Component
public class GpCatalogParser {

    private final ObjectMapper mapper;

    public GpCatalogParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public List<GpRecord> parse(byte[] json) throws IOException {
        return mapper.readValue(json, new TypeReference<List<GpRecord>>() {});
    }

    public List<GpRecord> parse(InputStream json) throws IOException {
        return mapper.readValue(json, new TypeReference<List<GpRecord>>() {});
    }
}
