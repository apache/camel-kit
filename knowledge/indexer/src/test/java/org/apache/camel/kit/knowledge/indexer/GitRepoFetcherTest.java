package org.apache.camel.kit.knowledge.indexer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class GitRepoFetcherTest {

    private static final PersonIdent IDENTITY = new PersonIdent("Test fixture", "fixture@example.invalid");

    @TempDir
    Path tempDir;

    private Git upstream;
    private String repoUrl;
    private GitRepoFetcher fetcher;
    private RevCommit latest;

    @BeforeEach
    void createLocalRepository() throws Exception {
        upstream = Git.init().setDirectory(tempDir.resolve("upstream").toFile()).setInitialBranch("main").call();
        writeUpstream("document.md", "first version");
        writeUpstream("notes.txt", "not a Markdown document");
        writeUpstream("nested/other.md", "nested document");
        commit("first version");
        tag("v1");
        writeUpstream("document.md", "second version");
        latest = commit("second version");
        tag("v2");

        repoUrl = tempDir.resolve("upstream").toUri().toString();
        fetcher = new GitRepoFetcher(tempDir.resolve("cache"));
    }

    @AfterEach
    void closeLocalRepository() {
        if (upstream != null) {
            upstream.close();
        }
    }

    @Test
    void clonesBranchWithDepthOne() throws Exception {
        Path repoDir = fetcher.fetchRepo(repoUrl, "main", "documents");

        assertEquals(tempDir.resolve("cache/documents"), repoDir);
        assertEquals("second version", Files.readString(repoDir.resolve("document.md")));
        try (Git clone = Git.open(repoDir.toFile())) {
            assertEquals(latest.getId(), clone.getRepository().resolve("HEAD"));
            assertTrue(clone.getRepository().getObjectDatabase().getShallowCommits().contains(latest.getId()));
            var history = clone.log().call().iterator();
            assertEquals(latest.getId(), history.next().getId());
            assertFalse(history.hasNext(), "History from HEAD must stop at the shallow boundary");
        }
    }

    @Test
    void listsOnlyMatchingDocumentFiles() throws Exception {
        Path repoDir = fetcher.fetchRepo(repoUrl, "main", "documents");
        Files.createDirectory(repoDir.resolve("directory.md"));

        assertEquals(List.of(repoDir.resolve("document.md")), fetcher.listFiles(repoDir, "*.md"));
        assertEquals(List.of(repoDir.resolve("document.md"), repoDir.resolve("nested/other.md")),
                fetcher.listFilesRecursive(repoDir, "*.md").stream().sorted().toList());
        assertEquals("nested document", Files.readString(repoDir.resolve("nested/other.md")));
        assertTrue(fetcher.listFiles(repoDir.resolve("missing"), "*.md").isEmpty());
        assertTrue(fetcher.listFilesRecursive(repoDir.resolve("missing"), "*.md").isEmpty());
    }

    @Test
    void updatesExistingCloneAndResetsTrackedContents() throws Exception {
        Path repoDir = fetcher.fetchRepo(repoUrl, "main", "documents", false);
        Path sentinel = repoDir.resolve(".git/retained-on-update");
        Files.writeString(sentinel, "original repository");
        Files.writeString(repoDir.resolve("document.md"), "local changes");
        Files.delete(repoDir.resolve("nested/other.md"));
        writeUpstream("document.md", "third version");
        writeUpstream("added.md", "new upstream document");
        RevCommit next = commit("third version");

        assertEquals(repoDir, fetcher.fetchRepo(repoUrl, "main", "documents", false));

        assertEquals("third version", Files.readString(repoDir.resolve("document.md")));
        assertEquals("nested document", Files.readString(repoDir.resolve("nested/other.md")));
        assertEquals("new upstream document", Files.readString(repoDir.resolve("added.md")));
        assertEquals("original repository", Files.readString(sentinel), "An update must not replace the clone");
        try (Git clone = Git.open(repoDir.toFile())) {
            assertEquals(next.getId(), clone.getRepository().resolve("HEAD"));
            assertTrue(clone.status().call().isClean());
        }
    }

    @Test
    void listsAnnotatedTagsWithoutPeeledDuplicates() throws Exception {
        assertEquals(List.of("v1", "v2"), GitRepoFetcher.listRemoteTags(repoUrl).stream().sorted().toList());
    }

    @Test
    void checksOutRequestedTag() throws Exception {
        Path repoDir = fetcher.fetchRepo(repoUrl, "v1", "documents", true);

        assertEquals("first version", Files.readString(repoDir.resolve("document.md")));
        assertEquals("v1", Files.readString(repoDir.resolve(".fetched-tag")));
        try (Git clone = Git.open(repoDir.toFile())) {
            assertEquals(upstream.getRepository().resolve("v1^{commit}"), clone.getRepository().resolve("HEAD"));
        }
    }

    @Test
    void reusesUnchangedTagWithoutRemoteAccess() throws Exception {
        Path repoDir = fetcher.fetchRepo(repoUrl, "v1", "documents", true);
        Path sentinel = repoDir.resolve(".git/retained-on-cache-hit");
        Files.writeString(sentinel, "cached repository");
        upstream.close();
        upstream = null;
        Files.move(tempDir.resolve("upstream"), tempDir.resolve("offline-upstream"));

        assertEquals(repoDir, fetcher.fetchRepo(repoUrl, "v1", "documents", true));

        assertEquals("first version", Files.readString(repoDir.resolve("document.md")));
        assertEquals("cached repository", Files.readString(sentinel));
    }

    @Test
    void replacesCloneWhenRequestedTagChanges() throws Exception {
        Path repoDir = fetcher.fetchRepo(repoUrl, "v1", "documents", true);
        Path sentinel = repoDir.resolve(".git/replaced-on-tag-change");
        Files.writeString(sentinel, "old tag");

        assertEquals(repoDir, fetcher.fetchRepo(repoUrl, "v2", "documents", true));

        assertEquals("second version", Files.readString(repoDir.resolve("document.md")));
        assertEquals("v2", Files.readString(repoDir.resolve(".fetched-tag")));
        assertFalse(Files.exists(sentinel));
        try (Git clone = Git.open(repoDir.toFile())) {
            assertEquals(latest.getId(), clone.getRepository().resolve("HEAD"));
        }
    }

    @Test
    void recoversIncompleteClone() throws Exception {
        Path repoDir = tempDir.resolve("cache/documents");
        Files.createDirectories(repoDir.resolve("partial"));
        Path partial = repoDir.resolve("partial/download.md");
        Files.writeString(partial, "interrupted clone");

        assertEquals(repoDir, fetcher.fetchRepo(repoUrl, "main", "documents"));

        assertTrue(Files.isDirectory(repoDir.resolve(".git")));
        assertEquals("second version", Files.readString(repoDir.resolve("document.md")));
        assertFalse(Files.exists(partial));
    }

    @Test
    void recoversCloneWithInvalidGitDirectory() throws Exception {
        Path repoDir = tempDir.resolve("cache/documents");
        Files.createDirectories(repoDir.resolve(".git"));
        Path partial = repoDir.resolve("partial.md");
        Files.writeString(partial, "interrupted clone");

        assertEquals(repoDir, fetcher.fetchRepo(repoUrl, "main", "documents"));

        assertEquals("second version", Files.readString(repoDir.resolve("document.md")));
        assertFalse(Files.exists(partial));
        try (Git clone = Git.open(repoDir.toFile())) {
            assertEquals(latest.getId(), clone.getRepository().resolve("HEAD"));
            assertTrue(clone.status().call().isClean());
        }
    }

    @Test
    void reclonesFromSuppliedUrlAfterUpdateFails() throws Exception {
        Path repoDir = fetcher.fetchRepo(repoUrl, "main", "documents");
        Path sentinel = repoDir.resolve(".git/replaced-after-failure");
        Files.writeString(sentinel, "original repository");
        try (Git clone = Git.open(repoDir.toFile())) {
            var config = clone.getRepository().getConfig();
            config.setString("remote", "origin", "url", repoUrl + "-missing");
            config.save();
        }
        writeUpstream("document.md", "third version");
        RevCommit next = commit("third version");

        assertEquals(repoDir, fetcher.fetchRepo(repoUrl, "main", "documents"));

        assertEquals("third version", Files.readString(repoDir.resolve("document.md")));
        assertFalse(Files.exists(sentinel));
        try (Git clone = Git.open(repoDir.toFile())) {
            assertEquals(next.getId(), clone.getRepository().resolve("HEAD"));
            assertEquals(repoUrl, clone.getRepository().getConfig().getString("remote", "origin", "url"));
        }
    }

    @Test
    void removesFailedCloneAndReportsRequestedRepositoryAndRef() {
        IOException failure = assertThrows(IOException.class,
                () -> fetcher.fetchRepo(repoUrl, "missing-branch", "documents"));

        assertTrue(failure.getMessage().contains("Failed to clone " + repoUrl + " at missing-branch"));
        assertInstanceOf(GitAPIException.class, failure.getCause());
        assertFalse(Files.exists(tempDir.resolve("cache/documents")));
    }

    @Test
    void explicitRefreshReplacesExistingClone() throws Exception {
        Path repoDir = fetcher.fetchRepo(repoUrl, "main", "documents");
        Path sentinel = repoDir.resolve(".git/replaced-on-refresh");
        Files.writeString(sentinel, "original repository");
        Files.writeString(repoDir.resolve("untracked.md"), "remove during refresh");
        writeUpstream("document.md", "third version");
        RevCommit next = commit("third version");

        assertEquals(repoDir, fetcher.refreshRepo(repoUrl, "main", "documents"));

        assertEquals("third version", Files.readString(repoDir.resolve("document.md")));
        assertFalse(Files.exists(sentinel));
        assertFalse(Files.exists(repoDir.resolve("untracked.md")));
        try (Git clone = Git.open(repoDir.toFile())) {
            assertEquals(next.getId(), clone.getRepository().resolve("HEAD"));
        }
    }

    private void writeUpstream(String relativePath, String content) throws Exception {
        Path path = upstream.getRepository().getWorkTree().toPath().resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private RevCommit commit(String message) throws Exception {
        upstream.add().addFilepattern(".").call();
        return upstream.commit().setMessage(message).setAuthor(IDENTITY).setCommitter(IDENTITY)
                .setSign(false).setNoVerify(true).call();
    }

    private void tag(String name) throws Exception {
        upstream.tag().setName(name).setAnnotated(true).setMessage(name).setTagger(IDENTITY).setSigned(false).call();
    }
}
