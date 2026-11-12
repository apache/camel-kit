package org.apache.camel.kit.generator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

import org.tomlj.Toml;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

final class CodexConfigMerger {

    static final String BEGIN_MARKER = "# BEGIN CAMEL-KIT MANAGED MCP SERVERS";
    static final String END_MARKER = "# END CAMEL-KIT MANAGED MCP SERVERS";

    static List<String> managedServers(boolean citrusEnabled) {
        return citrusEnabled
                ? List.of("camel", "camel-knowledge", "citrus")
                : List.of("camel", "camel-knowledge");
    }

    /** Writes the merged configuration. Everything up to the write is {@link #candidate}. */
    void merge(Path configFile, String renderedConfig, boolean citrusEnabled) throws IOException {
        AtomicFileWriter.write(configFile, candidate(configFile, renderedConfig, citrusEnabled));
    }

    /**
     * Runs every check of {@link #merge} without writing, so a preview and an initialization reject the same input.
     */
    void validate(Path configFile, String renderedConfig, boolean citrusEnabled)
            throws InvalidAgentConfigurationException {
        try {
            candidate(configFile, renderedConfig, citrusEnabled);
        } catch (IOException e) {
            throw new InvalidAgentConfigurationException(
                    "Invalid Codex MCP config " + configFile + ": " + e.getMessage()
                                                         + ". Fix the file before retrying.",
                    e);
        }
    }

    /**
     * Builds and validates the text {@link #merge} would write: the existing configuration with the managed block added
     * or replaced. Reads only.
     */
    String candidate(Path configFile, String renderedConfig, boolean citrusEnabled) throws IOException {
        validateToml(renderedConfig, "generated Codex MCP configuration");

        String managedBlock = BEGIN_MARKER + System.lineSeparator()
                              + renderedConfig.strip() + System.lineSeparator()
                              + END_MARKER;
        String existing = "";
        if (Files.exists(configFile, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(configFile, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("existing .codex/config.toml must be a regular file");
            }
            existing = Files.readString(configFile);
        }
        String merged = merge(existing, managedBlock, citrusEnabled);
        // Catches what the checks on the existing file cannot name, such as an inline or scalar mcp_servers.
        TomlParseResult parsed = Toml.parse(merged);
        if (parsed.hasErrors()) {
            throw new IOException(
                    "existing .codex/config.toml cannot hold the Camel-Kit MCP server tables, the merged file would"
                                  + " not be valid TOML: " + parsed.errors().get(0));
        }
        return merged + (merged.endsWith(System.lineSeparator()) ? "" : System.lineSeparator());
    }

    private String merge(String existing, String managedBlock, boolean citrusEnabled) throws IOException {
        if (existing.isBlank()) {
            return managedBlock;
        }

        validateToml(existing, "existing .codex/config.toml");
        int begin = existing.indexOf(BEGIN_MARKER);
        int end = existing.indexOf(END_MARKER);
        boolean hasBlock = begin >= 0 || end >= 0;
        if (hasBlock && (begin < 0 || end < begin || existing.indexOf(BEGIN_MARKER, begin + 1) >= 0
                || existing.indexOf(END_MARKER, end + 1) >= 0)) {
            throw new IOException("existing .codex/config.toml has invalid Camel-Kit managed block markers");
        }

        // The servers Camel-Kit manages must not also be defined outside its block, with or without a block.
        String before = hasBlock ? existing.substring(0, begin) : existing;
        String after = hasBlock ? existing.substring(end + END_MARKER.length()) : "";
        TomlParseResult outside = Toml.parse(before + after);
        if (!outside.hasErrors() && outside.get("mcp_servers") instanceof TomlTable servers) {
            List<String> conflicts = managedServers(citrusEnabled).stream()
                    .filter(server -> servers.get(server) instanceof TomlTable)
                    .toList();
            if (!conflicts.isEmpty()) {
                throw new IOException(
                        "existing .codex/config.toml already defines Camel-Kit MCP server tables: "
                                      + String.join(", ", conflicts));
            }
        }

        if (hasBlock) {
            return before + managedBlock + after;
        }
        return existing + (existing.endsWith(System.lineSeparator())
                ? System.lineSeparator()
                : System.lineSeparator() + System.lineSeparator())
               + managedBlock;
    }

    private void validateToml(String content, String source) throws IOException {
        TomlParseResult parsed = Toml.parse(content);
        if (parsed.hasErrors()) {
            throw new IOException(source + " is not valid TOML: " + parsed.errors().get(0));
        }
    }
}
