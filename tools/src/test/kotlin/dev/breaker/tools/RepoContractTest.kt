package dev.breaker.tools

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Structural contract for every module in the reactor.
 *
 * Replaces:
 *   tools/check_repo.py         — folder existence, card sections, README, cycles,
 *                                  unregistered folders, parent README sub-module lists
 *   tests/contract/             — per-module card/registry/bijection checks
 *   tools/gate.py steps 1-3     — check_repo, contract tier, unit tier
 *
 * Runs as part of ./mvnw clean install via surefire. No modules.toml.
 * Structure is read entirely from the POM reactor and the card files on disk.
 */
class RepoContractTest {

    private val repoRoot: File = run {
        var d = File(System.getProperty("breaker.repoRoot")
            ?: System.getenv("BREAKER_REPO_ROOT")
            ?: ".")
        if (!d.isAbsolute) d = d.absoluteFile
        d
    }

    private val ns = mapOf("m" to "http://maven.apache.org/POM/4.0.0")

    private val REQUIRED_CARD_SECTIONS = listOf(
        "## Purpose", "## Owns", "## Does Not Own", "## Public Interface",
        "## Depends On", "## Invariants", "## Test Locations",
        "## Test Requirement", "## Known Gotchas",
    )

    // Modules that have AGENTS.md but are not leaf build modules
    private val CARD_EXEMPT = setOf(".", "docs", "decisions", "tools", "contrib", "agent-skills")

    // ── POM reactor walking ───────────────────────────────────────────────

    private fun parsePom(path: String): Element {
        val file = if (path.isEmpty()) repoRoot.resolve("pom.xml")
                   else repoRoot.resolve(path).resolve("pom.xml")
        return DocumentBuilderFactory.newInstance()
            .newDocumentBuilder().parse(file).documentElement
    }

    private fun Element.children(tag: String): List<Element> {
        val nodes = getElementsByTagName(tag); val out = mutableListOf<Element>()
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as Element
            if (e.parentNode == this) out.add(e)
        }
        return out
    }

    private fun Element.text(tag: String): String? {
        val nodes = getElementsByTagName(tag)
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as Element
            if (e.parentNode == this) return e.textContent?.trim()?.takeIf { it.isNotEmpty() }
        }
        return null
    }

    data class Module(
        val artifactId: String,
        val path: String,
        val packaging: String,
        val deps: Set<String>,   // dev.breaker artifactIds only
    )

    private fun reactorModules(rel: String = ""): Map<String, Module> {
        val out = mutableMapOf<String, Module>()
        val root = parsePom(rel)
        val packaging = root.text("packaging") ?: "jar"
        val artifactId = root.text("artifactId") ?: return out
        val deps = root.children(
            root.getElementsByTagName("dependencies").item(0)?.let { "dependency" } ?: ""
        ).let {
            root.getElementsByTagName("dependency").let { nl ->
                val set = mutableSetOf<String>()
                for (i in 0 until nl.length) {
                    val d = nl.item(i) as Element
                    if (d.text("groupId") == "dev.breaker")
                        d.text("artifactId")?.let { set.add(it) }
                }
                set
            }
        }
        out[artifactId] = Module(artifactId, rel, packaging, deps)
        val modulesEl = root.getElementsByTagName("modules").item(0) as? Element
        modulesEl?.getElementsByTagName("module")?.let { nl ->
            for (i in 0 until nl.length) {
                val m = nl.item(i).textContent?.trim() ?: continue
                val childRel = if (rel.isEmpty()) m else "$rel/$m"
                out.putAll(reactorModules(childRel))
            }
        }
        return out
    }

    private val modules: Map<String, Module> by lazy { reactorModules() }

    // ── tests ────────────────────────────────────────────────────────────

    @Test
    fun `every module folder has AGENTS dot md and README dot md`() {
        val missing = modules.values
            .filter { it.packaging != "pom" }
            .flatMap { m ->
                listOf(
                    repoRoot.resolve(m.path).resolve("AGENTS.md") to "${m.path}/AGENTS.md",
                    repoRoot.resolve(m.path).resolve("README.md") to "${m.path}/README.md",
                ).filter { (f, _) -> !f.exists() }.map { (_, label) -> label }
            }
        assertTrue("missing files: $missing", missing.isEmpty())
    }

    @Test
    fun `every card carries the required sections`() {
        val problems = mutableListOf<String>()
        for (m in modules.values) {
            if (m.packaging == "pom") continue
            val card = repoRoot.resolve(m.path).resolve("AGENTS.md")
            if (!card.exists()) continue
            val text = card.readText()
            for (sec in REQUIRED_CARD_SECTIONS)
                if (sec !in text) problems.add("${m.path}/AGENTS.md missing '$sec'")
        }
        assertTrue("card problems: $problems", problems.isEmpty())
    }

    @Test
    fun `no module depends on itself`() {
        val cycles = modules.values.filter { it.artifactId in it.deps }
        assertTrue("self-deps: ${cycles.map { it.artifactId }}", cycles.isEmpty())
    }

    @Test
    fun `dependency edges stay within the reactor`() {
        val ids = modules.keys.toSet()
        val extra = modules.values.flatMap { m ->
            m.deps.filter { it !in ids }.map { "${m.artifactId} -> $it (not in reactor)" }
        }
        assertTrue("unknown deps: $extra", extra.isEmpty())
    }

    @Test
    fun `no dependency cycles`() {
        val adj = modules.mapValues { (_, m) -> m.deps.filter { it in modules } }
        val white = mutableSetOf<String>().apply { addAll(modules.keys) }
        val gray = mutableSetOf<String>()
        val cycles = mutableListOf<String>()
        fun dfs(u: String) {
            white.remove(u); gray.add(u)
            for (v in adj[u] ?: emptyList()) {
                if (v in gray) { cycles.add("$u -> $v"); return }
                if (v in white) dfs(v)
            }
            gray.remove(u)
        }
        while (white.isNotEmpty()) dfs(white.first())
        assertTrue("cycles: $cycles", cycles.isEmpty())
    }

    @Test
    fun `every folder with AGENTS dot md is in the reactor`() {
        val ids = modules.values.map { it.path }.toSet()
        val unregistered = mutableListOf<String>()
        repoRoot.walkTopDown()
            .filter { it.name == "AGENTS.md" && it.isFile }
            .forEach { f ->
                val rel = f.parentFile.relativeTo(repoRoot).path
                    .replace(File.separatorChar, '/')
                if (rel == ".") return@forEach
                val top = rel.split("/").first()
                if (top in CARD_EXEMPT) return@forEach
                if (rel !in ids) unregistered.add(rel)
            }
        assertTrue("unregistered folders with AGENTS.md: $unregistered", unregistered.isEmpty())
    }

    @Test
    fun `parent READMEs name their sub-modules`() {
        val problems = mutableListOf<String>()
        for (parent in listOf("android", "server", "shared")) {
            val readme = repoRoot.resolve(parent).resolve("README.md")
            if (!readme.exists()) continue
            val text = readme.readText()
            val line = text.lines().firstOrNull { it.trim().startsWith("**Sub-modules:**") }
            if (line == null) { problems.add("$parent/README.md has no **Sub-modules:** line"); continue }
            val named = line.substringAfter("**Sub-modules:**").substringBefore(" — ")
                .split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            val expected = modules.values
                .filter { it.path.startsWith("$parent/") }
                .map { it.path.substringAfterLast("/") }
                .toSet()
            (expected - named).forEach { problems.add("$parent/README.md missing sub-module '$it'") }
            (named - expected).forEach { problems.add("$parent/README.md names unregistered '$it'") }
        }
        assertTrue("README sub-module problems: $problems", problems.isEmpty())
    }

    @Test
    fun `reactor modules match what is built`() {
        // Canary: the reactor walk returns modules and all their paths exist.
        assertTrue("no modules found", modules.isNotEmpty())
        val missing = modules.values.filter { !repoRoot.resolve(it.path).exists() }
        assertTrue("reactor paths missing on disk: ${missing.map { it.path }}", missing.isEmpty())
    }

    @Test
    fun `NUL bytes are absent from tracked source and config files`() {
        val extensions = setOf("kt", "kts", "py", "md", "toml", "yaml", "yml", "xml")
        val violations = mutableListOf<String>()
        // Walk known source trees only — not target/ or .git/
        val skip = setOf("target", ".git", ".gradle", "node_modules")
        repoRoot.walkTopDown().onEnter { it.name !in skip }.filter { it.isFile }.forEach { f ->
            if (f.extension in extensions) {
                if (f.readBytes().contains(0.toByte())) {
                    violations.add(f.relativeTo(repoRoot).path)
                }
            }
        }
        assertTrue("NUL bytes in tracked files: $violations", violations.isEmpty())
    }
}
