package etcodehome.freeterraforged.platform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class GradlePropertiesPlatformTest {

    private static final String FORBIDDEN_TRUST_STORE_KEY = "javax.net.ssl.truststoretype";
    private static final String FORBIDDEN_WINDOWS_ROOT = "windows-root";
    private static final String FORBIDDEN_WINDOWS_MY = "windows-my";

    @Test
    @DisplayName("Verify gradle.properties does not contain Windows-only trust store platform arguments")
    void testProjectGradlePropertiesContainsNoWindowsTrustStoreArgs() throws IOException {
        Path rootDir = findProjectRoot();
        List<Path> gradlePropertiesFiles = findGradlePropertiesFiles(rootDir);

        assertTrue(!gradlePropertiesFiles.isEmpty(), "At least one gradle.properties file must be found in " + rootDir);

        for (Path file : gradlePropertiesFiles) {
            String content = Files.readString(file);
            validateNoWindowsTrustStoreArgs(content, file.toString());
        }
    }

    @Test
    @DisplayName("Verify validator accurately detects and rejects Windows-only trust store arguments")
    void testValidatorCatchesDisallowedArguments() {
        assertThrows(AssertionError.class, () ->
            validateNoWindowsTrustStoreArgs(
                "org.gradle.jvmargs=-Xmx4G -Djavax.net.ssl.trustStoreType=WINDOWS-ROOT",
                "test-gradle.properties"
            )
        );

        assertThrows(AssertionError.class, () ->
            validateNoWindowsTrustStoreArgs(
                "org.gradle.jvmargs=-Djavax.net.ssl.trustStoreType=Windows-ROOT",
                "test-gradle.properties"
            )
        );

        assertThrows(AssertionError.class, () ->
            validateNoWindowsTrustStoreArgs(
                "systemProp.javax.net.ssl.trustStoreType=WINDOWS-ROOT",
                "test-gradle.properties"
            )
        );

        assertThrows(AssertionError.class, () ->
            validateNoWindowsTrustStoreArgs(
                "org.gradle.jvmargs=-Djavax.net.ssl.trustStoreType=Windows-MY",
                "test-gradle.properties"
            )
        );

        // Standard clean arguments must pass without failure
        validateNoWindowsTrustStoreArgs("org.gradle.jvmargs=-Xmx4G\nminecraft_version=1.21.1", "clean-gradle.properties");
    }

    public static void validateNoWindowsTrustStoreArgs(String content, String sourceDescription) {
        String lower = content.toLowerCase(Locale.ROOT);
        if (lower.contains(FORBIDDEN_WINDOWS_ROOT) || lower.contains(FORBIDDEN_WINDOWS_MY)
            || (lower.contains(FORBIDDEN_TRUST_STORE_KEY) && lower.contains("windows"))) {
            fail(String.format(
                "Disallowed Windows-only trust store platform argument found in %s!%n"
                + "Windows-only trust store arguments (-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT) "
                + "are forbidden as they break Linux, macOS, and CI/CD builds.%n"
                + "Content: %s",
                sourceDescription,
                content.trim()
            ));
        }
    }

    private static Path findProjectRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve("settings.gradle")) || Files.exists(dir.resolve("settings.gradle.kts"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        Path fallback = Paths.get("..").toAbsolutePath().normalize();
        if (Files.exists(fallback.resolve("gradle.properties"))) {
            return fallback;
        }
        return Paths.get("").toAbsolutePath();
    }

    private static List<Path> findGradlePropertiesFiles(Path rootDir) throws IOException {
        List<Path> files = new ArrayList<>();
        Path rootProps = rootDir.resolve("gradle.properties");
        if (Files.exists(rootProps)) {
            files.add(rootProps);
        }
        try (Stream<Path> stream = Files.walk(rootDir, 2)) {
            stream.filter(p -> p.getFileName() != null && p.getFileName().toString().equals("gradle.properties"))
                  .filter(p -> !files.contains(p))
                  .forEach(files::add);
        }
        return files;
    }
}
