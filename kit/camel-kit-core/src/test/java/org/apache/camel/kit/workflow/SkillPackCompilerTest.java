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
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.camel.kit.CamelKitMain;
import org.apache.camel.kit.config.AgentRegistry;
import org.apache.camel.kit.doc.FrontmatterHandler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
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
        assertFalse(content(catalog, "camel-start/SKILL.md").contains("<!-- camel-kit:step"));
        assertArrayEquals(Files.readAllBytes(Path.of("src/main/resources/skills/shared/iron-laws.md")),
                catalog.open("shared/iron-laws.md").readAllBytes());
    }

    @Test
    void claudePreservesExistingCommandsThatShareAPackSkillName() throws Exception {
        Path pack = pack("inspect", "add: {skill/camel-inspect: {contract: 1, resource: inspect.md}}");
        write("inspect", "inspect.md", SKILL);
        Path distribution = distribution(pack, "inspect");
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        Path commands = Files.createDirectories(workspace.resolve(".claude/commands"));
        Files.writeString(commands.resolve("camel-inspect.md"), "My project command.");
        Files.writeString(commands.resolve("camel-start.md"), "Retired Kit stub.");
        assertCli(0, distribution, workspace, "init", "--here", "--ai", "claude", "--no-fetch");
        assertEquals("My project command.", Files.readString(commands.resolve("camel-inspect.md")));
        assertFalse(Files.exists(commands.resolve("camel-start.md")));
        assertTrue(Files.isRegularFile(workspace.resolve(".claude/skills/camel-inspect/SKILL.md")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"camel-design", "camel-implement", "camel-test"})
    void survivingInstructionsRequireGuidesFromExcludedSkills(String skill) throws Exception {
        Path pack
                = pack("minimal", "requires: [core]\nreplace: {skill/camel-start: {contract: 1, resource: router.md}}\n"
                                  + "exclude: [skill/" + skill + "]");
        write("minimal", "router.md", SKILL.replace("camel-inspect", "camel-start"));
        try (var loader = loader(pack)) {
            var error = assertThrows(IOException.class, () -> SkillPackCompiler.compile(loader, List.of("minimal")));
            assertTrue(error.getMessage().contains("resource/" + skill + "/guides/"), error::getMessage);
            assertTrue(error.getMessage().contains("minimal: skill/" + skill), error::getMessage);
        }
    }

    @Test
    void excludingOneGuideReportsItsSurvivingConsumerAndTheExcludingPack() throws Exception {
        String guide = "resource/camel-design/guides/performance.md";
        Path pack = pack("small", "requires: [core]\nexclude: [" + guide + "]");
        try (var loader = loader(pack)) {
            var error = assertThrows(IOException.class, () -> SkillPackCompiler.compile(loader, List.of("small")));
            assertTrue(error.getMessage().contains("core: "), error::getMessage);
            assertTrue(error.getMessage().contains("requires missing contribution " + guide), error::getMessage);
            assertTrue(error.getMessage().contains("small: " + guide), error::getMessage);
        }
    }

    @Test
    void coreMarkdownReferencesToOtherSkillsGuidesAreCatalogDependencies() throws Exception {
        var catalog = SkillCatalog.bundled();
        var bindings = catalog.manifest().contributions();
        Path sources = Path.of("src/main/resources/skills");
        for (var entry : bindings.entrySet()) {
            if (!(entry.getKey().startsWith("skill/") || entry.getKey().startsWith("resource/"))) {
                continue;
            }
            String path = entry.getValue().path();
            if (!path.endsWith(".md")) {
                continue;
            }
            String text = Files.readString(sources.resolve(path));
            String owner = path.substring(0, path.indexOf('/') + 1);
            for (String guide : catalog.paths()) {
                if (guide.contains("/guides/") && !guide.startsWith(owner) && text.contains(guide)) {
                    assertTrue(entry.getValue().requires().contains("resource/" + guide), path + " -> " + guide);
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"camel-start", "camel-inspect"})
    void replacingASkillMustPreserveItsDeclaredVisibility(String name) throws Exception {
        Path upstream = pack("inspect", "add: {skill/camel-inspect: {contract: 1, resource: entry.md}}");
        write("inspect", "entry.md", SKILL);
        Path overlay = pack("team", "requires: [core, inspect]\nreplace: {skill/" + name
                                    + ": {contract: 1, resource: entry.md}}");
        write("team", "entry.md", SKILL.replace("camel-inspect", name).replace("true", "false"));
        try (var loader = loader(upstream, overlay)) {
            var failure = assertThrows(IOException.class,
                    () -> SkillPackCompiler.compile(loader, List.of("inspect", "team")));
            String reason = name.equals("camel-start")
                    ? "user_invocable differs from the core skill contract"
                    : "replacement must preserve visibility declared by inspect: skill/camel-inspect";
            assertEquals("team: skill/" + name + ": " + reason, failure.getMessage());
        }
    }

    @Test
    void replacingTheConsumerAllowsExcludingItsGuide() throws Exception {
        Path upstream = pack("inspect", """
                add:
                  skill/camel-inspect: {contract: 1, resource: entry.md}
                  resource/camel-inspect/guides/check.md: {contract: 1, resource: check.md}
                """);
        write("inspect", "entry.md", SKILL + "Read `guides/check.md`.\n");
        write("inspect", "check.md", "Check the integration.");
        Path overlay = pack("team", """
                requires: [inspect]
                replace: {skill/camel-inspect: {contract: 1, resource: entry.md}}
                exclude: [resource/camel-inspect/guides/check.md]
                """);
        write("team", "entry.md", SKILL);
        try (var loader = loader(upstream, overlay)) {
            assertDoesNotThrow(() -> SkillPackCompiler.compile(loader, List.of("inspect", "team")));
        }
    }

    @Test
    void aPublicSkillNeedsARoutingMarkerInTheReplacementRouter() throws Exception {
        Path pack = pack("team", """
                requires: [core]
                add: {skill/camel-inspect: {contract: 1, resource: entry.md}}
                replace: {skill/camel-start: {contract: 1, resource: router.md}}
                """);
        write("team", "entry.md", SKILL);
        write("team", "router.md", SKILL.replace("camel-inspect", "camel-start"));
        try (var loader = loader(pack)) {
            var failure = assertThrows(IOException.class, () -> SkillPackCompiler.compile(loader, List.of("team")));
            assertEquals(
                    "team: skill/camel-start: expected one <!-- camel-kit:step choose --> marker for route/choose in camel-start/SKILL.md",
                    failure.getMessage());
        }
    }

    @Test
    void codexAdaptsCommandMentionsAndPreservesLiteralPaths() throws Exception {
        Path pack = pack("docs", "add: {skill/docs: {contract: 1, resource: docs.md}, "
                                 + "skill/camel-inspect: {contract: 1, resource: inspect.md}}");
        write("docs", "inspect.md", SKILL);
        String paths = "`/docs/index.md`, `/camel-inspect/notes.md`, `/camel-inspect.md`, "
                       + "`/docs_config`, `https://example.org/docs` and `[notes](/docs)`.";
        String literal = paths + "\n[manual]: /camel-inspect?view=raw\n[section]: /camel-inspect#overview"
                         + "\n[plain]: /camel-inspect\n[inline]( /camel-inspect) and /camel-inspect?view=raw";
        String prose = "Run /docs, then /camel-inspect... **/camel-inspect**? |/docs| /docs<br> Try /docs?";
        write("docs", "docs.md", SKILL.replace("camel-inspect", "docs") + prose + "\n" + literal + "\n");
        Path distribution = distribution(pack, "docs");
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        assertCli(0, distribution, workspace, "init", "--here", "--ai", "codex", "--no-fetch");
        assertTrue(Files.readString(workspace.resolve(".agents/skills/docs/SKILL.md"))
                .contains(
                        prose.replace("/docs", "$docs").replace("/camel-inspect", "$camel-inspect") + "\n" + literal));
    }

    private Path withoutKnowledge(String survivingCaller) throws IOException {
        StringBuilder replacements = new StringBuilder();
        for (String name : List.of("camel-start", "camel-brainstorm", "camel-execute", "camel-migrate")) {
            if (!name.equals(survivingCaller)) {
                replacements.append("  skill/").append(name).append(": {contract: 1, resource: ").append(name)
                        .append(".md}\n");
                boolean router = name.equals("camel-start");
                write("team", name + ".md",
                        SKILL.replace("camel-inspect", name).replace("true", Boolean.toString(router))
                                            + (router ? "<!-- camel-kit:step choose -->\n" : ""));
            }
        }
        write("team", "inspect.md", SKILL);
        return pack("team", "requires: [core]\nreplace:\n" + replacements
                            + "exclude: [skill/camel-knowledge, skill/camel-ship]\n"
                            + "add: {skill/camel-inspect: {contract: 1, resource: inspect.md}}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"camel-brainstorm", "camel-execute", "camel-migrate"})
    void eachCoreKnowledgeHandoffRequiresTheSkill(String caller) throws Exception {
        try (var loader = loader(withoutKnowledge(caller))) {
            var error = assertThrows(IOException.class, () -> SkillPackCompiler.compile(loader, List.of("team")));
            assertTrue(
                    error.getMessage().contains(
                            "core: skill/" + caller + ": requires missing contribution skill/camel-knowledge"),
                    error::getMessage);
            assertTrue(error.getMessage().contains("team: skill/camel-knowledge"), error::getMessage);
        }
    }

    @Test
    void checklistReadersDependOnTheChecklist() throws Exception {
        var bindings = SkillCatalog.bundled().manifest().contributions();
        String checklist = "resource/shared/camel-security-checklist.md";
        for (String guide : List.of("security", "monitoring")) {
            String reader = "resource/camel-design/guides/" + guide + ".md";
            assertTrue(bindings.get(reader).requires().contains(checklist), reader);
            assertFalse(bindings.get(checklist).requires().contains(reader), reader);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void artifactOwnersAreRequiredEvenWithoutAStage(boolean producer) throws Exception {
        String name = "camel-knowledge";
        String key = "skill/" + name;
        List<String> owners = List.of(name);
        var artifact = new WorkflowManifest.WorkflowArtifact(
                "report", "report.md",
                producer ? owners : List.of(), producer ? List.of() : owners);
        var core = WorkflowManifestLoader.load(WorkflowManifestLoader.DEFAULT_RESOURCE);
        var artifacts = new ArrayList<>(core.artifacts());
        artifacts.add(artifact);
        var workflow = new WorkflowManifest(
                core.version(), core.description(), core.commands(), core.skills(),
                core.stages(), artifacts, core.mcpServers(), core.websiteReference());
        WorkflowManifestValidator.validateOrThrow(workflow);
        var overlay = new SkillPack("team", 1, List.of("core"), Map.of(), Map.of(), List.of(key));
        var original = new SkillPack.Declaration("core", key, new SkillPack.Entry(1, "entry.md", null, List.of()));
        var failure = assertThrows(IOException.class,
                () -> SkillPack.resolve(Map.of("team", overlay), Map.of(key, original), workflow));
        assertEquals("team: " + key + ": required by core workflow artifact report", failure.getMessage());
    }

    @Test
    void installedContextUsesEffectivePublicSkills() throws Exception {
        Path distribution = distribution(withoutKnowledge(""), "team");
        String output = assertJava(0, distribution, temp, InstallAgents.class.getName());
        assertFalse(output.contains("/camel-knowledge"), output);
        for (String agent : AgentRegistry.names()) {
            Path workspace = temp.resolve(agent);
            for (String file : List.of("AGENTS.md", "CLAUDE.md", ".github/copilot-instructions.md")) {
                Path path = workspace.resolve(file);
                if (Files.exists(path)) {
                    String context = Files.readString(path);
                    assertFalse(context.contains("/camel-knowledge") || context.contains("Use `camel-knowledge`"),
                            agent + "/" + file);
                    assertFalse(context.contains("/camel-ship") || context.contains("$camel-ship"), agent + "/" + file);
                }
            }
            if (!List.of("codex", "pi").contains(agent)) {
                assertTrue(Files.readString(workspace.resolve("AGENTS.md")).contains("camel-inspect"), agent);
            }
            String router = Files.readString(
                    workspace.resolve(AgentRegistry.get(agent).skillsDirectory()).resolve("camel-start/SKILL.md"));
            assertTrue(router.contains("camel-inspect"), agent);
            assertFalse(router.contains("camel-knowledge"), agent);
        }
        String modes = Files.readString(temp.resolve("bob2/.bob/custom_modes.yaml"));
        assertFalse(modes.contains("camel-ship-mode"));
        assertTrue(modes.contains("camel-brainstorm-mode"));
        assertTrue(Files.isRegularFile(temp.resolve("bob2/.bob/agents/camel-ship-worker.md")));
    }

    /** All hosts use one JVM and one assembled catalog, isolated from the default catalog in the test runner. */
    public static class InstallAgents {
        public static void main(String[] args) {
            for (String agent : AgentRegistry.names()) {
                String[] init = {"init", agent, "--ai", agent, "--no-fetch"};
                assertEquals(0, CamelKitMain.commandLine(new CamelKitMain(), init).execute(init), agent);
            }
        }
    }

    private Path distribution(Path pack, String id) throws Exception {
        Path distribution = temp.resolve("dist-" + id);
        Path archive = distribution.resolve(SkillCatalog.DISTRIBUTION);
        Files.createDirectories(archive.getParent());
        try (var loader = loader(pack)) {
            Files.write(archive, SkillPackCompiler.compile(loader, List.of(id)));
        }
        return distribution;
    }

    @Test
    void reinitializationRemovesOnlyUnmodifiedFilesFromThePreviousCatalog() throws Exception {
        Path source = pack("optional", """
                add:
                  skill/camel-optional: {contract: 1, resource: entry.md}
                  resource/camel-optional/guides/owned.md: {contract: 1, resource: guide.md}
                  resource/camel-optional/guides/edited.md: {contract: 1, resource: guide.md}
                """);
        write("optional", "entry.md", SKILL.replace("camel-inspect", "camel-optional"));
        write("optional", "guide.md", "Original guide.");
        Path before = distribution(source, "optional");
        Path overlay = pack("minimal", "requires: [optional]\nexclude: [skill/camel-optional]");
        Path after = temp.resolve("minimal-dist");
        Path archive = after.resolve(SkillCatalog.DISTRIBUTION);
        Files.createDirectories(archive.getParent());
        try (var loader = loader(source, overlay)) {
            Files.write(archive, SkillPackCompiler.compile(loader, List.of("optional", "minimal")));
        }
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        Path skill = workspace.resolve(".bob/skills/camel-optional");
        Path stub = workspace.resolve(".bob/commands/camel-optional.md");
        assertCli(0, before, workspace, "init", "--here", "--ai", "bob2", "--no-fetch", "--silent");
        Files.writeString(skill.resolve("guides/edited.md"), "My edits.");
        Files.writeString(skill.resolve("guides/user.md"), "My own guide.");
        assertTrue(Files.isRegularFile(stub));
        String preview = assertCli(0, after, workspace, "init", "--here", "--ai", "bob2", "--dry-run");
        assertTrue(preview.contains("remove") && preview.contains(".bob/commands/camel-optional.md"), preview);
        assertTrue(Files.isRegularFile(stub));
        assertTrue(Files.isRegularFile(skill.resolve("SKILL.md")));
        for (int i = 0; i < 2; i++) {
            assertCli(0, after, workspace, "init", "--here", "--ai", "bob2", "--force", "--no-fetch", "--silent");
            assertFalse(Files.exists(stub));
            assertFalse(Files.exists(skill.resolve("SKILL.md")));
            assertFalse(Files.exists(skill.resolve("guides/owned.md")));
            assertEquals("My edits.", Files.readString(skill.resolve("guides/edited.md")));
            assertEquals("My own guide.", Files.readString(skill.resolve("guides/user.md")));
        }
        assertCli(0, after, workspace, "doctor");
    }

    @Test
    void writeFailureIdentifiesTheCatalogAndFilesystemReason() throws Exception {
        Path output = Files.createFile(temp.resolve("file")).resolve("catalog.zip");
        var failure = assertThrows(IOException.class, () -> SkillPackCompiler.writeCatalog(output, new byte[0]));
        assertTrue(failure.getMessage().contains("Cannot write skill catalog " + output), failure::getMessage);
        assertTrue(failure.getMessage().contains(failure.getCause().getClass().getSimpleName()), failure::getMessage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"shared/IRON-LAWS.md", "shared/iron-laws.md/nested.md"})
    void catalogLoaderRejectsConflictingInstalledPaths(String extraPath) throws Exception {
        Path archive = temp.resolve(SkillCatalog.DISTRIBUTION);
        Files.createDirectories(archive.getParent());
        var catalog = SkillCatalog.bundled();
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry(SkillCatalog.MANIFEST));
            zip.write(SkillCatalog.JSON.writeValueAsBytes(catalog.manifest()));
            for (String path : catalog.paths()) {
                zip.putNextEntry(new ZipEntry("skills/" + path));
                catalog.open(path).transferTo(zip);
            }
            zip.putNextEntry(new ZipEntry("skills/" + extraPath));
        }
        try (var loader = loader(temp)) {
            var error = assertThrows(IOException.class, () -> SkillCatalog.load(loader));
            String reason = extraPath.endsWith("nested.md") ? "both file and directory" : "differ only by case";
            assertTrue(error.getMessage().contains(reason), error::getMessage);
            assertTrue(error.getMessage().contains("shared/iron-laws.md"), error::getMessage);
        }
    }

    @Test
    void catalogLoaderRejectsAnUnsupportedContract() throws Exception {
        Path archive = temp.resolve(SkillCatalog.DISTRIBUTION);
        Files.createDirectories(archive.getParent());
        var original = SkillCatalog.bundled().manifest();
        var future = new SkillCatalog.Manifest(2, original.packs(), original.workflow(), original.contributions());
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry(SkillCatalog.MANIFEST));
            zip.write(SkillCatalog.JSON.writeValueAsBytes(future));
        }
        try (var loader = loader(temp)) {
            assertEquals("Unsupported skill catalog contract: 2",
                    assertThrows(IOException.class, () -> SkillCatalog.load(loader)).getMessage());
        }
    }

    @Test
    void catalogLoaderRejectsTwoAssembledArchives() throws Exception {
        Path one = temp.resolve("module one");
        Path two = temp.resolve("module two");
        for (Path module : List.of(one, two)) {
            Path archive = module.resolve(SkillCatalog.DISTRIBUTION);
            Files.createDirectories(archive.getParent());
            Files.createFile(archive);
        }
        try (var loader = loader(one, two)) {
            var error = assertThrows(IOException.class, () -> SkillCatalog.load(loader));
            assertTrue(error.getMessage().startsWith("Expected one assembled Camel Kit skill catalog"),
                    error::getMessage);
            for (Path module : List.of(one, two)) {
                assertTrue(
                        error.getMessage()
                                .contains(module.resolve(SkillCatalog.DISTRIBUTION).toUri().toURL().toString()),
                        error::getMessage);
            }
        }
    }

    @Nested
    class ComposedDistribution {
        Path pack;
        Path overlay;
        Path exampleJar;
        String skill;
        String guide;
        String provider;
        byte[] first;
        Path distribution;

        @BeforeEach
        void assemble() throws Exception {
            Path example = Path.of("../../docs/modules/ROOT/examples/skill-pack/src/main/resources").toRealPath();
            skill = "  skill/camel-inspect: {contract: 1, resource: SKILL.md}\n";
            guide = "  resource/camel-inspect/guides/inspect.md: {contract: 1, resource: guide.md}\n";
            provider
                    = "  provider/list: {contract: 1, provider: {service: java.util.List, implementation: java.util.ArrayList}}\n";
            pack = pack("inspect", "add:\n" + skill + guide + provider);
            write("inspect", "SKILL.md", SKILL);
            write("inspect", "guide.md", "Keep this guide.");
            overlay = pack("team",
                    """
                            requires: [inspect, core]
                            replace:
                              skill/camel-inspect: {contract: 1, resource: SKILL.md}
                              provider/list: {contract: 1, provider: {service: java.util.List, implementation: java.util.LinkedList}}
                            exclude: [step/validate/report/timestamp]
                            """);
            write("team", "SKILL.md", SKILL.replace("Original", "Team"));
            exampleJar = temp.resolve("report-review.jar");
            try (var zip = new ZipOutputStream(Files.newOutputStream(exampleJar)); var files = Files.walk(example)) {
                for (Path path : files.filter(Files::isRegularFile).toList()) {
                    zip.putNextEntry(new ZipEntry(example.relativize(path).toString().replace('\\', '/')));
                    Files.copy(path, zip);
                    zip.closeEntry();
                }
            }
            try (var loader = loader(exampleJar, pack, overlay)) {
                first = SkillPackCompiler.compile(loader, List.of("team", "report-review", "inspect"));
            }
            distribution = temp.resolve("distribution");
            Path archive = distribution.resolve(SkillCatalog.DISTRIBUTION);
            Files.createDirectories(archive.getParent());
            Files.write(archive, first);
        }

        @Test
        void independentModuleAndDeclarationOrderProduceIdenticalCatalogs() throws Exception {
            pack("inspect", "add:\n" + provider + guide + skill);
            try (var loader = loader(overlay, pack, exampleJar)) {
                assertArrayEquals(first,
                        SkillPackCompiler.compile(loader, List.of("inspect", "team", "report-review")));
            }
        }

        @Test
        void archiveContainsTheSelectedProviderAndStepInstructions() throws Exception {
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
        }

        @Test
        void installedFilesMatchTheCatalogAndDoctorNamesAMissingGuide() throws Exception {
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
    }

    @Test
    void installsInternalSkillWithFlowStyleFrontmatterAndExampleMetadataInItsBody() throws Exception {
        Path source = pack("library", "add: {skill/camel-reader: {contract: 1, resource: reader.md}}");
        String description
                = "Read the route and list the affected integrations before proposing changes to its configuration.";
        write("library", "reader.md", """
                ---
                {name: camel-reader, description: DESCRIPTION, user_invocable: false}
                ---

                Example settings from an input project:
                ```yaml
                user-invocable: true
                disable-model-invocation: false
                ```
                """.replace("DESCRIPTION", description));
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
        assertEquals(description, header.get("description").textValue());
        assertTrue(yaml.contains(description));
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
            "requires: [absent]|Missing pack dependency: bad -> absent",
            "requires: [bad]|Cyclic pack dependency: bad -> bad",
            "add: {skill/camel-start: {contract: 1, resource: start.md}}|core: skill/camel-start",
            "add: {skill/shared: {contract: 1, resource: guide.md}}|bad: skill/shared: reserved skill name",
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
            "requires: [core]\nexclude: [skill/camel-start]|bad: skill/camel-start: required by core workflow stage route",
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
        return assertJava(status, distribution, cwd, CamelKitMain.class.getName(), args);
    }

    private static String assertJava(int status, Path distribution, Path cwd, String main, String... args)
            throws Exception {
        String classpath = distribution + File.pathSeparator + System.getProperty("java.class.path");
        List<String> command = new ArrayList<>(
                List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp", classpath, main));
        command.addAll(List.of(args));
        // Catalog tests exercise init and Doctor without invoking external Camel or JBang installations.
        ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true);
        builder.environment().put("PATH", Path.of(System.getProperty("java.home"), "bin").toString());
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(status, process.waitFor(), output);
        return output;
    }
}
