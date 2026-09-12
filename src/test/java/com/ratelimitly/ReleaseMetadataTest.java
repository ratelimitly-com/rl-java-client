package com.ratelimitly;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class ReleaseMetadataTest {
    private static String pom;

    @BeforeAll
    static void readPom() throws Exception {
        Path path = Path.of("pom.xml");
        assertFalse(Files.notExists(path), "tests must run from the Maven project directory");
        pom = Files.readString(path);
    }

    @Test
    void declaresPublicMavenProjectMetadata() {
        assertContains("<groupId>com.ratelimitly</groupId>");
        assertContains("<artifactId>ratelimitly-java-client</artifactId>");
        assertContains("<url>https://github.com/ratelimitly-com/rl-java-client</url>");
        assertContains("<name>MIT License</name>");
        assertContains("<distribution>repo</distribution>");
        assertContains("<id>wojciech-fraczak</id>");
        assertContains("<email>wojciech@ratelimitly.com</email>");
        assertContains("<connection>scm:git:https://github.com/ratelimitly-com/rl-java-client.git</connection>");
    }

    @Test
    void pinsTheCompleteReleaseToolchain() {
        assertContains("<maven.compiler.version>3.15.0</maven.compiler.version>");
        assertContains("<maven.jar.version>3.5.1</maven.jar.version>");
        assertContains("<maven.source.version>3.4.0</maven.source.version>");
        assertContains("<maven.javadoc.version>3.12.0</maven.javadoc.version>");
        assertContains("<maven.gpg.version>3.2.8</maven.gpg.version>");
        assertContains("<project.build.outputTimestamp>2026-01-01T00:00:00Z</project.build.outputTimestamp>");
        assertContains("<Automatic-Module-Name>com.ratelimitly.client</Automatic-Module-Name>");
    }

    @Test
    void mavenReleaseProfileAttachesSignsAndPublishesArtifacts() {
        String profile = blockContaining(pom, "profile", "<id>maven-release</id>");

        assertPluginExecution(profile, "maven-source-plugin", "package", "jar-no-fork");
        assertPluginExecution(profile, "maven-javadoc-plugin", "package", "jar");
        assertPluginExecution(profile, "maven-gpg-plugin", "verify", "sign");
        assertPluginExecution(profile, "maven-antrun-plugin", "verify", "run");
        assertFragment(profile, "<excludePackageNames>com.ratelimitly.internal</excludePackageNames>");

        assertFragment(profile, "<id>gitlab-maven</id>");
        assertFragment(profile, "<url>https://gitlab.com/api/v4/projects/86375734/packages/maven</url>");
        assertFalse(profile.contains("org.sonatype.central"));
        assertFragment(profile, "<bestPractices>true</bestPractices>");
    }

    private static void assertPluginExecution(
        String profile,
        String artifactId,
        String phase,
        String goal
    ) {
        String plugin = blockContaining(
            profile,
            "plugin",
            "<artifactId>" + artifactId + "</artifactId>"
        );
        assertFragment(plugin, "<phase>" + phase + "</phase>");
        assertFragment(plugin, "<goal>" + goal + "</goal>");
    }

    private static String blockContaining(String source, String element, String marker) {
        String open = "<" + element + ">";
        String close = "</" + element + ">";
        int searchFrom = 0;
        while (true) {
            int start = source.indexOf(open, searchFrom);
            assertTrue(start >= 0, () -> "missing " + element + " containing " + marker);
            int end = source.indexOf(close, start);
            assertTrue(end >= 0, () -> "unterminated " + element + " containing " + marker);
            String block = source.substring(start, end + close.length());
            if (block.contains(marker)) {
                return block;
            }
            searchFrom = end + close.length();
        }
    }

    private static void assertContains(String expected) {
        assertFragment(pom, expected);
    }

    private static void assertFragment(String source, String expected) {
        assertTrue(source.contains(expected), () -> "missing POM fragment: " + expected);
    }
}
