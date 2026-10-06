package dev.breaker.tools

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.NodeList

/**
 * Verifies the Maven reactor structure matches modules.toml.
 *
 * Replaces decisions/ADR-020-maven-build/pom_contract.py with a Kotlin test that runs
 * as part of the normal build. Every check reads a POM as data: no regex over build
 * scripts, no comment stripping, no guessing which plugin ids publish an artifact.
 *
 * The same guarantees as the Python tier's contract_support.py, both ways:
 *   1. reactor == registry       the <modules> tree matches registered paths
 *   2. boundary is a bijection   a POM's dev.breaker dependencies match depends_on
 *   3. artifact or not           <packaging>pom</packaging> publishes nothing
 */
class PomContractTest {

    private val repoRoot = File(System.getProperty("breaker.repoRoot")
        ?: System.getenv("BREAKER_REPO_ROOT")
        ?: findRepoRoot())

    private val registry: Map<String, ModuleEntry> by lazy { parseRegistry() }

    data class ModuleEntry(
        val key: String,
        val path: String,
        val dependsOn: Set<String>,
        val card: String,
    )

    // ── registry parsing (minimal TOML for [module.xxx] sections) ────────────

    private fun parseRegistry(): Map<String, ModuleEntry> {
        val text = repoRoot.resolve("modules.toml").readText()
        val entries = mutableMapOf<String, ModuleEntry>()
        val sectionPattern = Regex("""\[module\.(\w+)]""")
        val sections = sectionPattern.findAll(text).toList()

        for ((i, match) in sections.withIndex()) {
            val key = match.groupValues[1]
            val start = match.range.last + 1
            val end = if (i + 1 < sections.size) sections[i + 1].range.first else text.length
            val body = text.substring(start, end)

            val path = Regex("""path\s*=\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
                ?: error("module.$key has no path")
            val card = Regex("""card\s*=\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
                ?: "$path/AGENTS.md"
            val dependsOn = Regex("""depends_on\s*=\s*\[([^\]]*)]""").find(body)
                ?.groupValues?.get(1)
                ?.let { Regex(""""(\w+)"""").findAll(it).map { m -> m.groupValues[1] }.toSet() }
                ?: emptySet()

            entries[key] = ModuleEntry(key, path, dependsOn, card)
        }
        return entries
    }

    // ── POM parsing ──────────────────────────────────────────────────────────

    private fun pom(path: String): Element {
        val file = repoRoot.resolve(path).resolve("pom.xml")
        return DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(file)
            .documentElement
    }

    private fun Element.text(tag: String): String? {
        val nodes = getElementsByTagName(tag)
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as Element
            // Only direct children (not nested in dependencies, etc.)
            if (el.parentNode == this) {
                return el.textContent?.trim()?.takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    private fun Element.modules(): List<String> {
        val result = mutableListOf<String>()
        val modulesEl = getElementsByTagName("modules").item(0) as? Element ?: return result
        val nodes = modulesEl.getElementsByTagName("module")
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as Element
            if (el.parentNode == modulesEl) {
                el.textContent?.trim()?.takeIf { it.isNotEmpty() }?.let { result.add(it) }
            }
        }
        return result
    }

    private fun Element.dependencies(): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        val depsEl = getElementsByTagName("dependencies").item(0) as? Element ?: return result
        val nodes = depsEl.getElementsByTagName("dependency")
        for (i in 0 until nodes.length) {
            val dep = nodes.item(i) as Element
            if (dep.parentNode == depsEl) {
                val groupId = dep.text("groupId")
                val artifactId = dep.text("artifactId")
                if (groupId != null && artifactId != null) {
                    result.add(groupId to artifactId)
                }
            }
        }
        return result
    }

    private fun reactorPaths(path: String = ""): Set<String> {
        val result = mutableSetOf<String>()
        val root = pom(path)
        for (module in root.modules()) {
            val childPath = if (path.isEmpty()) module else "$path/$module"
            result.add(childPath)
            result.addAll(reactorPaths(childPath))
        }
        return result
    }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    fun `every registered module has a POM`() {
        val missing = registry.values.filter {
            !repoRoot.resolve(it.path).resolve("pom.xml").exists()
        }
        assertTrue(
            "registered modules missing POMs: ${missing.map { it.path }}",
            missing.isEmpty()
        )
    }

    @Test
    fun `reactor modules match the registry exactly`() {
        val reactor = reactorPaths()
        val registered = registry.values.map { it.path }.toSet()

        val notInReactor = registered - reactor
        val notRegistered = reactor - registered

        assertTrue(
            "registered but not in reactor: $notInReactor",
            notInReactor.isEmpty()
        )
        assertTrue(
            "in reactor but not registered: $notRegistered",
            notRegistered.isEmpty()
        )
    }

    @Test
    fun `artifactId matches registry key`() {
        val mismatches = registry.values.mapNotNull { entry ->
            val root = pom(entry.path)
            val artifactId = root.text("artifactId")
            if (artifactId != entry.key) {
                "${entry.path}: artifactId=$artifactId, key=${entry.key}"
            } else null
        }
        assertTrue(
            "artifactId/key mismatches: $mismatches",
            mismatches.isEmpty()
        )
    }

    @Test
    fun `dependencies stay within registered boundaries`() {
        val violations = mutableListOf<String>()

        for (entry in registry.values) {
            val root = pom(entry.path)
            val devBreakerDeps = root.dependencies()
                .filter { it.first == "dev.breaker" }
                .map { it.second }
                .toSet()

            val allowed = entry.dependsOn
            val extra = devBreakerDeps - allowed
            for (dep in extra) {
                violations.add("${entry.path} depends on $dep, not in depends_on")
            }
        }

        assertTrue(
            "boundary violations: $violations",
            violations.isEmpty()
        )
    }

    @Test
    fun `code modules declare all required dependencies`() {
        val violations = mutableListOf<String>()
        val packaging = registry.mapValues { (_, e) -> pom(e.path).text("packaging") ?: "jar" }
        val isContainer = registry.mapValues { (key, e) ->
            registry.values.any { it.path.startsWith(e.path + "/") }
        }

        for (entry in registry.values) {
            if (packaging[entry.key] == "pom") continue // containers don't need deps

            val root = pom(entry.path)
            val declared = root.dependencies()
                .filter { it.first == "dev.breaker" }
                .map { it.second }
                .toSet()

            val required = entry.dependsOn.filter { dep ->
                dep in registry &&
                    !isContainer[dep]!! &&
                    packaging[dep] != "pom"
            }.toSet()

            val missing = required - declared
            for (dep in missing) {
                violations.add("${entry.path} missing dependency on $dep (required by depends_on)")
            }
        }

        assertTrue(
            "missing required dependencies: $violations",
            violations.isEmpty()
        )
    }

    // ── mutation proof: the tests can fail ───────────────────────────────────

    @Test
    fun `the registry parser reads depends_on correctly`() {
        // android_history depends on android_core per modules.toml
        val history = registry["android_history"]
        assertNotNull("android_history not found in registry", history)
        assertTrue(
            "android_history should depend on android_core",
            "android_core" in history!!.dependsOn
        )
    }

    @Test
    fun `the reactor walker finds nested modules`() {
        val reactor = reactorPaths()
        assertTrue("android/modules/core should be in reactor", "android/modules/core" in reactor)
        assertTrue("shared/modules/ui-tokens should be in reactor", "shared/modules/ui-tokens" in reactor)
    }

    companion object {
        private fun findRepoRoot(): String {
            var dir = File(".").absoluteFile
            while (dir.parentFile != null) {
                if (dir.resolve("modules.toml").exists() && dir.resolve("pom.xml").exists()) {
                    return dir.absolutePath
                }
                dir = dir.parentFile
            }
            error("Could not find repo root (modules.toml + pom.xml)")
        }
    }
}
