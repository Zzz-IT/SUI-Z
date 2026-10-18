/*
 * This file is part of Sui.
 *
 * Sui is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Sui is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Sui.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (c) 2021-2026 Sui Contributors
 */

package rikka.sui.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * Unit tests for {@link ShellConfigRules}.
 *
 * <p>Every case here mirrors a rule that is also implemented natively in
 * {@code module/src/main/cpp/main/sui_main.hpp}. If a test is deleted or
 * loosened, the Java and native implementations can drift apart without any
 * signal; that drift breaks the shell server silently rather than loudly.
 */
public class ShellConfigRulesTest {

    // ------------------------------------------------------------------
    // isValidShellDirName
    // ------------------------------------------------------------------

    @Test
    public void isValidShellDirName_acceptsLegacyName() {
        // Existing installs still point at the fixed name; rejecting it would
        // strand them with a shell server that can never read its config.
        assertTrue(ShellConfigRules.isValidShellDirName("sui_shell"));
    }

    @Test
    public void isValidShellDirName_acceptsRandomizedName() {
        assertTrue(ShellConfigRules.isValidShellDirName("sui_shell_0123456789abcdef"));
        assertTrue(ShellConfigRules.isValidShellDirName("sui_shell_"));
        assertTrue(ShellConfigRules.isValidShellDirName("sui_shell_a-b_c"));
    }

    @Test
    public void isValidShellDirName_rejectsNullAndEmpty() {
        assertFalse(ShellConfigRules.isValidShellDirName(null));
        assertFalse(ShellConfigRules.isValidShellDirName(""));
    }

    @Test
    public void isValidShellDirName_rejectsMissingPrefix() {
        assertFalse(ShellConfigRules.isValidShellDirName("shell"));
        assertFalse(ShellConfigRules.isValidShellDirName("sui_shel"));
        assertFalse(ShellConfigRules.isValidShellDirName("suishell_a"));
    }

    @Test
    public void isValidShellDirName_rejectsPathTraversal() {
        // The resolved name becomes part of a path that shell scripts and the
        // shell server both walk. A separator here would escape the base dir.
        assertFalse(ShellConfigRules.isValidShellDirName("sui_shell_../../etc"));
        assertFalse(ShellConfigRules.isValidShellDirName("sui_shell_a/b"));
    }

    @Test
    public void isValidShellDirName_rejectsShellMetacharacters() {
        // Marker content is attacker-influenced once /data/adb/sui is readable
        // by a compromised root consumer. Metacharacters must not survive.
        assertFalse(ShellConfigRules.isValidShellDirName("sui_shell_$(reboot)"));
        assertFalse(ShellConfigRules.isValidShellDirName("sui_shell_`id`"));
        assertFalse(ShellConfigRules.isValidShellDirName("sui_shell_a;b"));
        assertFalse(ShellConfigRules.isValidShellDirName("sui_shell_a|b"));
        assertFalse(ShellConfigRules.isValidShellDirName("sui_shell_a&b"));
        assertFalse(ShellConfigRules.isValidShellDirName("sui_shell_a b"));
    }

    @Test
    public void isValidShellDirName_rejectsDotSegments() {
        // Dot is excluded from the alphabet, so "." and ".." cannot appear and
        // traversal via dot segments is impossible even without a separator.
        assertFalse(ShellConfigRules.isValidShellDirName("sui_shell_."));
        assertFalse(ShellConfigRules.isValidShellDirName("sui_shell_.."));
        assertFalse(ShellConfigRules.isValidShellDirName("sui_shell_a.b"));
    }

    @Test
    public void isValidShellDirName_rejectsUppercase() {
        // The native implementation scans only [0-9a-z_-]. Accepting uppercase
        // on the Java side would produce a path the shell server cannot resolve.
        assertFalse(ShellConfigRules.isValidShellDirName("sui_shell_ABCDEF"));
        assertFalse(ShellConfigRules.isValidShellDirName("SUI_SHELL_a"));
    }

    // ------------------------------------------------------------------
    // resolveShellDir
    // ------------------------------------------------------------------

    @Test
    public void resolveShellDir_acceptsBareName() {
        assertEquals(
                "/data/local/tmp/sui_shell_0123456789abcdef",
                ShellConfigRules.resolveShellDir("sui_shell_0123456789abcdef"));
    }

    @Test
    public void resolveShellDir_acceptsAbsolutePath() {
        // The marker may hold either form depending on which release wrote it.
        assertEquals(
                "/data/local/tmp/sui_shell_0123456789abcdef",
                ShellConfigRules.resolveShellDir("/data/local/tmp/sui_shell_0123456789abcdef"));
    }

    @Test
    public void resolveShellDir_trimsSurroundingWhitespace() {
        // Marker files are written with a trailing newline.
        assertEquals(
                "/data/local/tmp/sui_shell_0123456789abcdef",
                ShellConfigRules.resolveShellDir("sui_shell_0123456789abcdef\n"));
        assertEquals(
                "/data/local/tmp/sui_shell_0123456789abcdef",
                ShellConfigRules.resolveShellDir("  sui_shell_0123456789abcdef  "));
    }

    @Test
    public void resolveShellDir_acceptsLegacyName() {
        assertEquals("/data/local/tmp/sui_shell", ShellConfigRules.resolveShellDir("sui_shell"));
        assertEquals("/data/local/tmp/sui_shell", ShellConfigRules.resolveShellDir("/data/local/tmp/sui_shell"));
    }

    @Test
    public void resolveShellDir_rejectsNullAndBlank() {
        assertNull(ShellConfigRules.resolveShellDir(null));
        assertNull(ShellConfigRules.resolveShellDir(""));
        assertNull(ShellConfigRules.resolveShellDir("   "));
        assertNull(ShellConfigRules.resolveShellDir("\n"));
    }

    @Test
    public void resolveShellDir_rejectsPathOutsideBaseDir() {
        // A marker must never be able to redirect the shell server somewhere
        // the shell uid was not given access to.
        assertNull(ShellConfigRules.resolveShellDir("/data/adb/sui"));
        assertNull(ShellConfigRules.resolveShellDir("/etc"));
        assertNull(ShellConfigRules.resolveShellDir("/data/local/tmp/../etc"));
    }

    @Test
    public void resolveShellDir_rejectsTraversalInsideBaseDir() {
        assertNull(ShellConfigRules.resolveShellDir("/data/local/tmp/sui_shell_../../etc"));
        assertNull(ShellConfigRules.resolveShellDir("sui_shell_../../etc"));
    }

    @Test
    public void resolveShellDir_rejectsBaseDirPrefixWithoutName() {
        assertNull(ShellConfigRules.resolveShellDir("/data/local/tmp/"));
        assertNull(ShellConfigRules.resolveShellDir("/data/local/tmp"));
    }

    // ------------------------------------------------------------------
    // isLegacyShellDirName
    // ------------------------------------------------------------------

    @Test
    public void isLegacyShellDirName_onlyMatchesLegacy() {
        assertTrue(ShellConfigRules.isLegacyShellDirName("sui_shell"));
        assertFalse(ShellConfigRules.isLegacyShellDirName("sui_shell_abc"));
        assertFalse(ShellConfigRules.isLegacyShellDirName("/data/local/tmp/sui_shell"));
        assertFalse(ShellConfigRules.isLegacyShellDirName(null));
    }

    // ------------------------------------------------------------------
    // Constants: the native side hardcodes these strings.
    // ------------------------------------------------------------------

    @Test
    public void constants_matchNativeImplementation() {
        // sui_main.hpp: SHELL_BASE_DIR, SHELL_DIR_PREFIX, SHELL_DIR_MARKER.
        // A silent change to any of these desynchronises root and shell servers.
        assertEquals("/data/local/tmp", ShellConfigRules.SHELL_BASE_DIR);
        assertEquals("sui_shell_", ShellConfigRules.SHELL_DIR_PREFIX);
        assertEquals("sui_shell", ShellConfigRules.LEGACY_SHELL_DIR_NAME);
        assertEquals("/data/adb/sui/shell_dir_name", ShellConfigRules.SHELL_DIR_MARKER);
    }

    // ------------------------------------------------------------------
    // Cross-language guard: the native header must agree with this class.
    // ------------------------------------------------------------------

    private static final String NATIVE_HEADER_RELATIVE_PATH = "module/src/main/cpp/main/sui_main.hpp";

    @Test
    public void nativeHeaderConstants_matchThisClass() throws Exception {
        // Pinning the Java literals above only catches a Java-side edit. Nothing used to
        // look at sui_main.hpp, so editing the header alone -- the more likely mistake,
        // since it is the copy nobody compiles on a phone-less machine -- produced two
        // servers that disagreed about the shell workdir name, and every app silently lost
        // its shell permission. Read the header and compare the actual string literals.
        String header = readNativeHeader();

        assertEquals(ShellConfigRules.SHELL_BASE_DIR, nativeStringConstant(header, "SHELL_BASE_DIR"));
        assertEquals(ShellConfigRules.SHELL_DIR_PREFIX, nativeStringConstant(header, "SHELL_DIR_PREFIX"));
        assertEquals(
                ShellConfigRules.LEGACY_SHELL_DIR_NAME,
                nativeStringConstant(header, "LEGACY_SHELL_DIR_NAME"));
        assertEquals(ShellConfigRules.SHELL_DIR_MARKER, nativeStringConstant(header, "SHELL_DIR_MARKER"));

        // The legacy absolute path is derived, not independent: check it too so a change to
        // either part cannot leave a stale absolute path behind.
        assertEquals(
                ShellConfigRules.SHELL_BASE_DIR + "/" + ShellConfigRules.LEGACY_SHELL_DIR_NAME,
                nativeStringConstant(header, "LEGACY_SHELL_DIR"));
    }

    @Test
    public void nativeHeader_usesTheLegacyNameConstantEverywhere() throws Exception {
        // is_valid_shell_dir_name() and resolve_shell_dir_path() used to compare against the
        // literal "sui_shell" instead of the constant, so the constant could be renamed while
        // those comparisons stayed behind and the legacy directory stopped being recognised.
        String header = readNativeHeader();
        assertEquals(
                "LEGACY_SHELL_DIR_NAME should be declared exactly once",
                1,
                countOccurrences(header, "const char* LEGACY_SHELL_DIR_NAME"));
        // Two occurrences are legitimate: the constant declaration itself, and the argv[0]
        // process name handed to app_process (a name, not a directory). A third one means a
        // path comparison was written against the literal again.
        assertEquals(
                "the legacy name should appear only as the constant and as the app_process name",
                2,
                countOccurrences(header, "\"sui_shell\""));
    }

    private static String readNativeHeader() throws Exception {
        File dir = new File("").getAbsoluteFile();
        for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParentFile()) {
            File candidate = new File(dir, NATIVE_HEADER_RELATIVE_PATH);
            if (candidate.isFile()) {
                return new String(Files.readAllBytes(candidate.toPath()), StandardCharsets.UTF_8);
            }
        }
        fail("cannot locate " + NATIVE_HEADER_RELATIVE_PATH + " from "
                + new File("").getAbsolutePath() + "; the native/Java constant guard cannot run");
        return null;
    }

    private static String nativeStringConstant(String header, String name) {
        Pattern pattern = Pattern.compile(
                "static\\s+constexpr\\s+const\\s+char\\s*\\*\\s*" + Pattern.quote(name)
                        + "\\s*=\\s*\"([^\"]*)\"\\s*;");
        Matcher matcher = pattern.matcher(header);
        if (!matcher.find()) {
            fail("constant " + name + " is not declared as a static constexpr const char* in "
                    + NATIVE_HEADER_RELATIVE_PATH);
            return null;
        }
        return matcher.group(1);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return count;
    }
}
