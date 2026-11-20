package org.apache.camel.kit.workflow;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.camel.kit.doc.FrontmatterHandler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class SkillPackCompilerTest {

    @TempDir
    Path temp;
    private static final String REVIEW = "List the routes that the validation run covered";
    private static final String SKILL = """
            ---
            name: camel-inspect
            description: Inspect an integration.
            user_invocable: true
            ---
            Original instructions.
            """;

    @Test
    void defaultCatalogPreservesPublicCommandsInternalSkillsAndCoreResources() throws Exception {
        var catalog = SkillCatalog.bundled();
        WorkflowManifest workflow = catalog.manifest().workflow();
        assertEquals(WorkflowManifestLoader.load(WorkflowManifestLoader.DEFAULT_RESOURCE), workflow);
        assertEquals(Set.of("camel-start", "camel-brainstorm", "camel-plan", "camel-migrate", "camel-execute",
                "camel-validate", "camel-debug", "camel-ship", "camel-knowledge"),
                workflow.generatedCommandStubs().stream().map(c -> c.name()).collect(Collectors.toSet()));
        assertEquals(Set.of("camel-test", "camel-design", "camel-implement", "camel-verify"), workflow.skills().stream()
                .filter(s -> "internal".equals(s.status())).map(s -> s.name()).collect(Collectors.toSet()));
        assertEquals("camel-validate/SKILL.md",
                catalog.manifest().contributions().get("step/validate/report/timestamp").path());
        assertTrue(content(catalog, "camel-validate/SKILL.md").contains("Use the current date and time"));
        assertArrayEquals(Files.readAllBytes(Path.of("src/main/resources/skills/shared/iron-laws.md")),
                catalog.open("shared/iron-laws.md").readAllBytes());
    }

    @Test
    void independentModuleAndDeclarationOrderProduceIdenticalCatalogs() throws Exception {
        Path example = Path.of("../../docs/modules/ROOT/examples/skill-pack/src/main/resources").toRealPath();
        String skill = "  skill/camel-inspect: {contract: 1, resource: SKILL.md}\n";
        String guide = "  resource/camel-inspect/guides/inspect.md: {contract: 1, resource: guide.md}\n";
        String provider
                = "  provider/list: {contract: 1, provider: {service: java.util.List, implementation: java.util.ArrayList}}\n";
        Path pack = pack("inspect", "add:\n" + skill + guide + provider);
        write("inspect", "SKILL.md", SKILL);
        write("inspect", "guide.md", "Keep this guide.");
        Path overlay = pack("team",
                """
                        requires: [inspect, core]
                        replace:
                          skill/camel-inspect: {contract: 1, resource: SKILL.md}
                          provider/list: {contract: 1, provider: {service: java.util.List, implementation: java.util.LinkedList}}
                        exclude: [step/validate/report/timestamp]
                        """);
        write("team", "SKILL.md", SKILL.replace("Original", "Team"));
        Path exampleJar = temp.resolve("report-review.jar");
        try (var zip = new ZipOutputStream(Files.newOutputStream(exampleJar)); var files = Files.walk(example)) {
            for (Path path : files.filter(Files::isRegularFile).toList()) {
                zip.putNextEntry(new ZipEntry(example.relativize(path).toString().replace('\\', '/')));
                Files.copy(path, zip);
                zip.closeEntry();
            }
        }
        byte[] first;
        try (var loader = loader(exampleJar, pack, overlay)) {
            first = SkillPackCompiler.compile(loader, List.of("team", "report-review", "inspect"));
        }
        pack("inspect", "add:\n" + provider + guide + skill);
        try (var loader = loader(overlay, pack, exampleJar)) {
            assertArrayEquals(first, SkillPackCompiler.compile(loader, List.of("inspect", "team", "report-review")));
        }
        Path distribution = temp.resolve("distribution");
        Path archive = distribution.resolve(SkillCatalog.DISTRIBUTION);
        Files.createDirectories(archive.getParent());
        Files.write(archive, first);
        // Consume the assembled distribution without any input pack directories or JARs on the runtime classpath.
        try (var loader = loader(distribution)) {
            SkillCatalog catalog = SkillCatalog.load(loader);
            assertEquals("java.util.LinkedList",
                    catalog.manifest().contributions().get("provider/list").provider().implementation());
            String validate = content(catalog, "camel-validate/SKILL.md");
            assertTrue(validate.indexOf("## Validation Report\n") < validate.indexOf(REVIEW));
            assertTrue(validate.indexOf(REVIEW) < validate.indexOf("### Report Format"));
            assertFalse(validate.contains("Use the current date and time for the timestamped filename"));
        }
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        assertCli(0, distribution, workspace, "init", "--here", "--ai", "bob2", "--no-fetch");
        Path skills = workspace.resolve(".bob/skills");
        assertTrue(Files.readString(skills.resolve("camel-validate/SKILL.md")).contains(REVIEW));
        assertTrue(Files.readString(skills.resolve("camel-inspect/SKILL.md")).contains("Team instructions."));
        assertTrue(Files.readString(skills.resolve("camel-start/SKILL.md"))
                .contains("/camel-inspect — Inspect an integration."));
        assertTrue(Files.readString(workspace.resolve(".bob/commands/camel-inspect.md"))
                .contains(".bob/skills/camel-inspect/SKILL.md"));
        Path installedGuide = skills.resolve("camel-inspect/guides/inspect.md");
        assertEquals("Keep this guide.", Files.readString(installedGuide));
        assertCli(0, distribution, workspace, "doctor");
        Files.delete(installedGuide);
        assertTrue(assertCli(1, distribution, workspace, "doctor").contains("camel-inspect/guides/inspect.md"));
    }

    @Test
    void installsInternalSkillWithFlowStyleFrontmatterAndExampleMetadataInItsBody() throws Exception {
        Path source = pack("library", "add: {skill/camel-reader: {contract: 1, resource: reader.md}}");
        write("library", "reader.md", """
                ---
                {name: camel-reader, description: Read the route., user_invocable: false}
                ---

                Example settings from an input project:
                ```yaml
                user-invocable: true
                disable-model-invocation: false
                ```
                """);
        Path archive = temp.resolve(SkillCatalog.DISTRIBUTION);
        Files.createDirectories(archive.getParent());
        try (var loader = loader(source)) {
            Files.write(archive, SkillPackCompiler.compile(loader, List.of("library")));
        }
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        assertCli(0, temp, workspace, "init", "--here", "--ai", "claude", "--no-fetch");
        String installed = Files.readString(workspace.resolve(".claude/skills/camel-reader/SKILL.md"));
        String yaml = FrontmatterHandler.extractFrontmatterYaml(installed);
        var header = SkillPack.YAML.readTree(yaml);
        assertFalse(header.get("user-invocable").booleanValue());
        assertTrue(header.get("disable-model-invocation").booleanValue());
        assertTrue(installed.contains("```yaml\nuser-invocable: true\ndisable-model-invocation: false\n```"));
    }

    @Test
    void excludingAPackSkillRemovesItsCommandRouteAndOwnedGuides() throws Exception {
        Path pack = pack("inspect", """
                add:
                  skill/camel-inspect: {contract: 1, resource: SKILL.md}
                  resource/camel-inspect/guides/extra.md: {contract: 1, resource: extra.md}
                """);
        write("inspect", "SKILL.md", SKILL);
        write("inspect", "extra.md", "Extra.");
        Path overlay = pack("small", "requires: [inspect]\nexclude: [skill/camel-inspect]\n");
        Path out = temp.resolve(SkillCatalog.DISTRIBUTION);
        Files.createDirectories(out.getParent());
        try (var loader = loader(pack, overlay)) {
            Files.write(out, SkillPackCompiler.compile(loader, List.of("small", "inspect")));
        }
        try (var loader = loader(temp)) {
            var catalog = SkillCatalog.load(loader);
            assertFalse(catalog.paths().stream().anyMatch(s -> s.startsWith("camel-inspect/")));
            assertFalse(
                    catalog.manifest().workflow().commands().stream().anyMatch(c -> c.name().equals("camel-inspect")));
            assertFalse(content(catalog, "camel-start/SKILL.md").contains("/camel-inspect"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "requires: [absent]|absent",
            "requires: [bad]|bad -> bad",
            "add: {skill/camel-start: {contract: 1, resource: start.md}}|core: skill/camel-start",
            "add: {step/validate/report/id: {contract: 2, resource: guide.md}}|unsupported contribution contract 2",
            "add: {step/validate/report/id: {contract: 1.5, resource: guide.md}}|1.5",
            "add: {step/validate/missing/id: {contract: 1, resource: guide.md}}|validate/missing",
            "add: {step/missing/report/id: {contract: 1, resource: guide.md}}|unknown workflow stage missing",
            "add: {step/validate/report/id: {contract: 1, resource: missing.md}}|missing.md",
            "add: {resource/absent/guide.md: {contract: 1, resource: guide.md}}|requires effective skill/absent",
            "add: {provider/check: {contract: 1, provider: {service: java.lang.Runnable, implementation: no.SuchClass}}}|no.SuchClass",
            "add: {provider/check: {contract: 1, provider: {service: java.lang.Runnable, implementation: java.util.ArrayList}}}|implementation of java.lang.Runnable",
            "exclude: [skill/unknown]|no such contribution",
            "exclude: [skill/camel-start]|requires must name",
            "requires: [core]\nexclude: [skill/camel-start]|route",
            "add: {step/validate/report/id: {contract: 1, resource: guide.md, requires: [provider/missing]}}|requires missing contribution provider/missing",
            "unknown: true|unknown",
            "exclude: []\nexclude: []|Duplicate"
    })
    void malformedDeclarationsIdentifyTheFailure(String scenario) throws Exception {
        String[] parts = scenario.split("\\|");
        Path pack = pack("bad", parts[0]);
        write("bad", "guide.md", "Additional guidance.");
        write("bad", "start.md", SKILL.replace("camel-inspect", "camel-start"));
        try (var loader = loader(pack)) {
            var failure = assertThrows(IOException.class, () -> SkillPackCompiler.compile(loader, List.of("bad")));
            assertTrue(failure.getMessage().contains(parts[1]), failure::getMessage);
        }
    }

    @Test
    void rejectsCompetingReplacementsAndAmbiguousResourceOrigins() throws Exception {
        String replace = """
                requires: [core]
                replace:
                  step/validate/report/timestamp: {contract: 1, resource: guide.md}
                """;
        Path one = pack("one", replace);
        Path two = pack("two", replace);
        write("one", "guide.md", "One.");
        write("two", "guide.md", "Two.");
        try (var loader = loader(two, one)) {
            IOException failure
                    = assertThrows(IOException.class, () -> SkillPackCompiler.compile(loader, List.of("two", "one")));
            assertTrue(failure.getMessage().contains("two: step/validate/report/timestamp: conflicts with one:"),
                    failure::getMessage);
        }
        Path other = temp.resolve("other");
        Path duplicate = other.resolve(SkillPack.directory("one")).resolve("guide.md");
        Files.createDirectories(duplicate.getParent());
        Files.writeString(duplicate, "Ambiguous.");
        try (var loader = loader(one, other)) {
            IOException failure
                    = assertThrows(IOException.class, () -> SkillPackCompiler.compile(loader, List.of("one")));
            assertTrue(failure.getMessage().contains("one: step/validate/report/timestamp: Expected one resource"),
                    failure::getMessage);
        }
    }

    @Test
    void rejectsDuplicatePackIdsUnsupportedSchemasAndIncompatibleProviderReplacements() throws Exception {
        Path source = pack("upstream",
                "add: {provider/check: {contract: 1, provider: {service: java.util.List, implementation: java.util.ArrayList}}}");
        Path overlay = pack("team", """
                requires: [upstream]
                replace:
                  provider/check: {contract: 1, provider: {service: java.util.Set, implementation: java.util.HashSet}}
                """);
        try (var loader = loader(source, overlay)) {
            var duplicate = assertThrows(IOException.class,
                    () -> SkillPackCompiler.compile(loader, List.of("upstream", "upstream")));
            assertTrue(duplicate.getMessage().contains("duplicate selected pack ID: upstream"), duplicate::getMessage);
            var service = assertThrows(IOException.class,
                    () -> SkillPackCompiler.compile(loader, List.of("upstream", "team")));
            assertTrue(service.getMessage().contains(
                    "team: provider/check: replacement must preserve service java.util.List declared by upstream:"),
                    service::getMessage);
            write("team", "pack.yaml", "id: team\nschema: 9\n");
            var schema = assertThrows(IOException.class, () -> SkillPackCompiler.compile(loader, List.of("team")));
            assertTrue(schema.getMessage().contains("'team' schema 9"), schema::getMessage);
        }
    }

    private Path pack(String id, String yaml) throws IOException {
        write(id, "pack.yaml", "id: " + id + "\nschema: 1\n" + yaml + "\n");
        return temp.resolve(id);
    }

    private void write(String id, String file, String text) throws IOException {
        Path path = temp.resolve(id).resolve(SkillPack.directory(id)).resolve(file);
        Files.createDirectories(path.getParent());
        Files.writeString(path, text);
    }

    private static URLClassLoader loader(Path... modules) throws IOException {
        List<URL> urls = new ArrayList<>();
        for (Path module : modules) {
            urls.add(module.toUri().toURL());
        }
        return new URLClassLoader(urls.toArray(URL[]::new), SkillPackCompilerTest.class.getClassLoader());
    }

    private static String content(SkillCatalog catalog, String file) throws IOException {
        return new String(catalog.open(file).readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String assertCli(int status, Path distribution, Path cwd, String... args) throws Exception {
        String classpath = distribution + File.pathSeparator + System.getProperty("java.class.path");
        List<String> command = new ArrayList<>(
                List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp", classpath, "org.apache.camel.kit.CamelKitMain"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(status, process.waitFor(), output);
        return output;
    }
}
