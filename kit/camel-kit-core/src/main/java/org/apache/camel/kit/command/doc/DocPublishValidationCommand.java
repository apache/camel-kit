package org.apache.camel.kit.command.doc;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.apache.camel.kit.command.CliCommand;
import org.apache.camel.kit.command.KitOptionParser;
import org.apache.camel.kit.command.PathConverter;
import org.apache.camel.kit.doc.DocumentationEvidence;
import org.apache.camel.kit.util.AtomicFileWriter;

import org.aesh.command.CommandDefinition;
import org.aesh.command.option.Option;

@CommandDefinition(name = "publish-validation", description = "Save a completed project or pipeline validation report")
public class DocPublishValidationCommand extends CliCommand {
    private static final DateTimeFormatter REPORT_STAMP
            = DateTimeFormatter.ofPattern("uuuu-MM-dd_HH-mm", Locale.ROOT).withZone(ZoneOffset.UTC);

    @Option(parser = KitOptionParser.class, name = "project", required = true, converter = PathConverter.class)
    Path project;

    @Option(parser = KitOptionParser.class, name = "input", required = true, converter = PathConverter.class)
    Path input;

    @Option(parser = KitOptionParser.class, name = "pipeline", description = "Explicit manual pipeline ID")
    String pipeline;

    @Override
    public Integer call() {
        try {
            Path root = project.toRealPath();
            Path draft = DocumentationEvidence.resolve(root, input);
            Path docs = DocumentationEvidence.resolve(root, Path.of("docs"));
            if (!docs.equals(draft.getParent())
                    || !draft.getFileName().toString().matches("validation-report-draft-[a-zA-Z0-9_-]+\\.md")
                    || !Files.isRegularFile(draft) || Files.size(draft) == 0) {
                throw new IOException("Expected a nonempty docs/validation-report-draft-<name>.md");
            }
            Path destination;
            if (pipeline != null) {
                if (!pipeline.matches("[0-9]{3}-[a-z0-9]+(?:-[a-z0-9]+)*")) {
                    throw new IOException("Invalid manual pipeline ID: " + pipeline);
                }
                destination = DocumentationEvidence.resolve(root,
                        Path.of("docs", "camel-kit", pipeline, "validation-report.md"));
                if (!Files.isDirectory(destination.getParent())) {
                    throw new IOException("Missing pipeline directory: " + destination.getParent());
                }
                AtomicFileWriter.replace(draft, destination);
            } else {
                String stamp = REPORT_STAMP.format(Instant.now());
                String name = "validation-report-" + stamp;
                for (int i = 1;; i++) {
                    destination = docs.resolve(name + (i == 1 ? "" : "-" + i) + ".md");
                    try {
                        Files.createFile(destination);
                        break;
                    } catch (FileAlreadyExistsException occupied) {
                        // Two checks in the same minute each retain their own report.
                    }
                }
                try {
                    AtomicFileWriter.replace(draft, destination);
                } catch (IOException e) {
                    Files.deleteIfExists(destination);
                    throw e;
                }
            }
            out.println(root.relativize(destination));
            return 0;
        } catch (IOException e) {
            err.println(e.getMessage());
            return 1;
        }
    }
}
