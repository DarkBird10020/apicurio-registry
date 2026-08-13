package io.apicurio.registry.cli;

import io.apicurio.registry.cli.config.Config;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static io.apicurio.registry.cli.InstallCommand.CONFIG_KEY_GLOBAL_INSTALL;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for how {@link UpdateCommand} decides an update's scope.
 *
 * <p>The scope marker is read through {@link Config#read()}, which resolves {@code config.json} under
 * the running binary's own home ({@code getAcrCurrentHomePath()}), so an update of a global install
 * must re-install with {@code --global}, while a per-user install must not. This locks in the
 * "auto-update works for global installs" behavior.
 */
@QuarkusTest
public class UpdateCommandTest {

    @Inject
    Config config;

    @Inject
    CommandLine.IFactory factory;

    @AfterEach
    public void tearDown() {
        config.reset();
    }

    @Test
    public void testUpdateForwardsGlobalFlagWhenInstallIsGlobal(@TempDir Path tempDir) throws Exception {
        final UpdateCommand updateCommand = prepareUpdateForHomeContaining(tempDir,
                "{\"config\":{\"" + CONFIG_KEY_GLOBAL_INSTALL + "\":\"true\"}}");

        final List<String> command = updateCommand.buildInstallCommand(Path.of("acr"));

        assertThat(command)
            .as("An update of a global install must re-install with --global")
            .contains("install", "--global");
    }

    @Test
    public void testUpdateDoesNotForwardGlobalFlagForPerUserInstall(@TempDir Path tempDir) throws Exception {
        final UpdateCommand updateCommand = prepareUpdateForHomeContaining(tempDir, "{}");

        final List<String> command = updateCommand.buildInstallCommand(Path.of("acr"));

        assertThat(command)
            .as("An update of a per-user install must not add --global")
            .contains("install")
            .doesNotContain("--global");
    }

    @Test
    public void testPostponeWithinCapReportsActualHours(@TempDir Path tempDir) throws Exception {
        prepareUpdateForHomeContaining(tempDir, "{}");

        final StringBuilder out = new StringBuilder();
        final var originalOut = config.getStdOut();
        config.setStdOut(out::append);
        try {
            new CommandLine(new Acr(), factory).execute("update", "--postpone", "24");
        } finally {
            config.setStdOut(originalOut);
        }

        assertThat(out.toString())
            .as("Confirmation should report the hours that were actually stored (24 <= cap)")
            .contains("24 hours");
    }

    @Test
    public void testPostponeAboveCapReportsClampedHours(@TempDir Path tempDir) throws Exception {
        prepareUpdateForHomeContaining(tempDir, "{}");

        final StringBuilder out = new StringBuilder();
        final var originalOut = config.getStdOut();
        config.setStdOut(out::append);
        try {
            // 999999 >> MAX_POSTPONE_HOURS (8760); the clamped value must appear in the message.
            new CommandLine(new Acr(), factory).execute("update", "--postpone", "999999");
        } finally {
            config.setStdOut(originalOut);
        }

        assertThat(out.toString())
            .as("Confirmation must report the clamped cap (8760), not the raw input (999999)")
            .contains(String.valueOf(UpdateCommand.MAX_POSTPONE_HOURS))
            .doesNotContain("999999");
    }

    /**
     * Writes a config.json with the given contents into a fresh home, points the running binary's
     * home at it (as the acr launcher would when 'acr update' runs), and returns a wired-up command.
     */
    private UpdateCommand prepareUpdateForHomeContaining(final Path tempDir, final String configJson)
            throws Exception {
        final Path home = tempDir.resolve("home");
        Files.createDirectories(home);
        Files.writeString(home.resolve("config.json"), configJson);

        config.reset();
        config.setAcrCurrentHomePath(home);

        return factory.create(UpdateCommand.class);
    }
}
