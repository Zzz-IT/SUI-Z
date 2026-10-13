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
}
