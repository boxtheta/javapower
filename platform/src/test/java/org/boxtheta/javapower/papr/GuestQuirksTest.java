package org.boxtheta.javapower.papr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Every quirk id in the code has its entry in docs/guest-quirks.md, and every entry an id in the code. */
class GuestQuirksTest {
    /** An entry's heading: "## `id`: title". */
    private static final Pattern HEADING = Pattern.compile("(?m)^## `([a-z0-9-]+)`");

    @Test
    void theQuirksFileListsExactlyTheQuirksInTheCode() throws IOException {
        Path doc = Path.of("docs", "guest-quirks.md");
        if (!Files.exists(doc)) {
            doc = Path.of("..", "docs", "guest-quirks.md"); // Gradle runs the tests in the module's directory
        }
        assertTrue(Files.exists(doc), "docs/guest-quirks.md");
        List<String> ids = new ArrayList<>();
        Matcher m = HEADING.matcher(Files.readString(doc));
        while (m.find()) {
            ids.add(m.group(1));
        }
        assertEquals(GuestQuirks.ALL, ids);
    }
}
