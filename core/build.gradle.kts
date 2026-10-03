import android.databinding.tool.ext.capitalizeUS
import com.github.kr328.golang.GolangBuildTask
import com.github.kr328.golang.GolangPlugin

plugins {
    kotlin("android")
    id("com.android.library")
    id("kotlinx-serialization")
    id("golang-android")
}

val golangSource = file("src/main/golang/native")

// The mihomo submodule HEAD, tracked as a task input. The Go builds compile
// `src/foss/golang` (module `foss`, `replace mihomo => ./clash`), but the golang
// tasks historically only tracked `src/main/golang/native` — so bumping the
// submodule left every golang task UP-TO-DATE and shipped a stale libclash.so.
// Tracking the commit (not the whole tree: ~6k files, and a plain checkout never
// mutates them) is enough to invalidate on every core bump.
val mihomoHead = providers.exec {
    commandLine("git", "-C", file("src/foss/golang/clash").absolutePath, "rev-parse", "HEAD")
}.standardOutput.asText.map { it.trim() }

// The mihomo release tag, resolved like CMakeLists.txt does for the Go side (exact
// tag of HEAD, else nearest release tag, else the require line in go.mod) and
// exposed as BuildConfig.CORE_TAG so Kotlin HTTP probes can advertise the same
// `mihomo/<version>` the native subscription fetch sends. Empty when unknown.
fun gitDescribe(vararg extra: String): String {
    val result = providers.exec {
        commandLine(
            listOf("git", "-C", file("src/foss/golang/clash").absolutePath, "describe", "--tags") +
                extra + listOf("--match", "v[0-9]*"),
        )
        isIgnoreExitValue = true
    }
    return if (result.result.get().exitValue == 0) result.standardOutput.asText.get().trim() else ""
}
val mihomoTag: String = gitDescribe("--exact-match").ifEmpty { gitDescribe("--abbrev=0") }.ifEmpty {
    val goMod = file("src/main/golang/go.mod")
    if (goMod.isFile) {
        Regex("""github\.com/metacubex/mihomo (v\d+\.\d+\.\d+)""").find(goMod.readText())?.groupValues?.get(1).orEmpty()
    } else {
        ""
    }
}

// ClashFest patch series for the mihomo submodule (core/patches/mihomo/*.patch).
//
// The submodule stays pinned to an upstream tag; the small deltas we need on
// top of it (see docs/core-patches.md) are applied to the working tree right
// before every Go build and test, and reverted by `clean`. The task works as a
// state machine on the tree itself, so a developer's stray edit can never be
// mistaken for the series:
//   * every patch reverse-applies cleanly  -> series already applied, no-op;
//   * tree clean and every patch applies    -> apply the series;
//   * anything else                          -> fail, naming the offending patch.
// A core bump that moves the patched lines therefore stops the build here, not
// after shipping an unpatched libclash.so.
val mihomoDir = file("src/foss/golang/clash")
val corePatchDir = file("patches/mihomo")

fun mihomoGit(vararg args: String): Boolean {
    val result = providers.exec {
        commandLine(listOf("git", "-C", mihomoDir.absolutePath) + args)
        isIgnoreExitValue = true
    }
    return result.result.get().exitValue == 0
}

fun corePatches(): List<File> =
    corePatchDir.listFiles { f -> f.isFile && f.name.endsWith(".patch") }.orEmpty().sortedBy { it.name }

val applyCorePatches by tasks.registering {
    description = "Apply the ClashFest patch series to the mihomo submodule working tree"
    group = "build setup"
    inputs.dir(corePatchDir)
    inputs.property("mihomoCommit", mihomoHead)
    outputs.upToDateWhen { false }
    doLast {
        val patches = corePatches()
        if (patches.isEmpty()) return@doLast
        val applied = patches.reversed().all { mihomoGit("apply", "--check", "-R", it.absolutePath) }
        if (applied) {
            logger.lifecycle("mihomo patches: ${patches.size} already applied")
            return@doLast
        }
        if (!mihomoGit("diff", "--quiet", "--exit-code")) {
            throw GradleException(
                "mihomo submodule has local modifications that are not the ClashFest patch series. " +
                    "Run `git -C core/src/foss/golang/clash checkout -- .` (or stash your work) and retry.",
            )
        }
        patches.forEach { patch ->
            if (!mihomoGit("apply", "--check", patch.absolutePath)) {
                throw GradleException(
                    "mihomo patch ${patch.name} no longer applies to submodule ${mihomoHead.get().take(12)}. " +
                        "Rebase the patch (git apply --3way) or drop it if upstream fixed it; see docs/core-patches.md.",
                )
            }
        }
        patches.forEach { patch ->
            if (!mihomoGit("apply", patch.absolutePath)) {
                throw GradleException("mihomo patch ${patch.name} failed to apply")
            }
            logger.lifecycle("mihomo patches: applied ${patch.name}")
        }
    }
}

val revertCorePatches by tasks.registering {
    description = "Remove the ClashFest patch series from the mihomo submodule working tree"
    group = "build setup"
    doLast {
        val patches = corePatches()
        if (patches.isEmpty()) return@doLast
        if (patches.reversed().all { mihomoGit("apply", "--check", "-R", it.absolutePath) }) {
            patches.reversed().forEach { mihomoGit("apply", "-R", it.absolutePath) }
            logger.lifecycle("mihomo patches: reverted ${patches.size}")
        }
    }
}

tasks.matching { it.name.startsWith("externalGolangBuild") }.configureEach {
    dependsOn(applyCorePatches)
}
tasks.matching { it.name == "clean" }.configureEach {
    dependsOn(revertCorePatches)
}

// Run pure-Go unit tests in the snapshot package before any Java/Kotlin
// compile. Snapshot is the engine-delegated read path for ClashFest UI
// (see docs/path-b-engine-parsing.md); we cannot afford it to silently
// regress when we change the Go-side data shape or mihomo upgrades.
//
// The package is intentionally isolated from cfa/native/app, so 'go test'
// works on any developer workstation (Windows/macOS/Linux) without
// platform stubs or NDK.
val goTestNativeSnapshot by tasks.registering(Exec::class) {
    description = "Run go unit tests for the native snapshot package"
    group = "verification"
    workingDir = file("src/main/golang")
    // Same build tags Gradle uses to compile the native libclash.so (see the
    // golang { } block below). config.ParseRawConfig pulls in symbols that
    // only exist under these tags (temporaryUpdateGeneral, etc), so go test
    // fails with "relocation target ... not defined" without them.
    commandLine(
        "go", "test", "-tags", "foss,with_gvisor,cmfa",
        "./native/snapshot/...", "./native/useragent/...", "./native/reality/...",
        // Tests shipped inside the patch series run against the patched tree, so a
        // patch that applied but no longer does what it claims fails here.
        "-run", "Test", "github.com/metacubex/mihomo/component/tls",
    )
    dependsOn(applyCorePatches)

    inputs.dir("src/main/golang/native/snapshot")
    inputs.dir("src/main/golang/native/useragent")
    // Re-run against a bumped core too: the test exercises mihomo itself
    // (module `cfa`, `replace mihomo => ../../foss/golang/clash`).
    inputs.property("mihomoCommit", mihomoHead)
    inputs.dir(corePatchDir)
    inputs.files("src/main/golang/go.mod", "src/main/golang/go.sum")
    val marker = layout.buildDirectory.file("go-tests/snapshot.passed")
    outputs.file(marker)
    doLast {
        marker.get().asFile.apply {
            parentFile.mkdirs()
            writeText("passed at ${System.currentTimeMillis()}\n")
        }
    }
}

tasks.withType(JavaCompile::class).configureEach {
    dependsOn(goTestNativeSnapshot)
}

golang {
    sourceSets {
        create("alpha") {
            tags.set(listOf("foss","with_gvisor","cmfa"))
            srcDir.set(file("src/foss/golang"))
        }
        create("meta") {
            tags.set(listOf("foss","with_gvisor","cmfa"))
            srcDir.set(file("src/foss/golang"))
        }
        all {
            fileName.set("libclash.so")
            packageName.set("cfa/native")
        }
    }
}

android {
    defaultConfig {
        buildConfigField("String", "CORE_TAG", "\"$mihomoTag\"")
    }

    productFlavors {
        all {
            externalNativeBuild {
                cmake {
                    arguments("-DGO_SOURCE:STRING=${golangSource}")
                    arguments("-DGO_OUTPUT:STRING=${GolangPlugin.outputDirOf(project, null, null)}")
                    arguments("-DFLAVOR_NAME:STRING=$name")
                }
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":common"))

    implementation(libs.androidx.core)
    implementation(libs.kotlin.coroutine)
    implementation(libs.kotlin.serialization.json)

    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
}

afterEvaluate {
    tasks.withType(GolangBuildTask::class.java).forEach {
        val task = it
        task.inputs.dir(golangSource)
        task.inputs.property("mihomoCommit", mihomoHead)
        task.inputs.files("src/foss/golang/go.mod", "src/foss/golang/go.sum")
        // The embedded Root CA bundle (//go:embed in component/ca/config.go).
        // It is an empty file in the submodule and CI populates it right
        // before the build (.github/scripts/populate-ca-bundle.sh); track it
        // so a populated bundle invalidates a stale libclash.so.
        task.inputs.files("src/foss/golang/clash/component/ca/ca-certificates.crt")
        task.doFirst {
            val command = task.commandLine.map { argument: Any -> argument.toString() }.toMutableList()
            val tagsIndex = command.indexOf("-tags")
            if (tagsIndex >= 0 && tagsIndex + 1 < command.size) {
                val tags = command[tagsIndex + 1]
                    .split(",")
                    .filter { tag: String -> tag != "debug" }

                if (tags.isEmpty()) {
                    command.removeAt(tagsIndex + 1)
                    command.removeAt(tagsIndex)
                } else {
                    command[tagsIndex + 1] = tags.joinToString(",")
                }

                task.commandLine(command)
            }
        }
    }
}

val abis = listOf("arm64-v8a" to "Arm64V8a", "armeabi-v7a" to "ArmeabiV7a", "x86" to "X86", "x86_64" to "X8664")

androidComponents.onVariants { variant ->
    val cmakeName = if (variant.buildType == "debug") "Debug" else "RelWithDebInfo"

    abis.forEach { (abi, goAbi) ->
        tasks.configureEach {
            if (name.startsWith("buildCMake$cmakeName[$abi]")) {
                dependsOn("externalGolangBuild${variant.name.capitalizeUS()}$goAbi")
                println("Set up dependency: $name -> externalGolangBuild${variant.name.capitalizeUS()}$goAbi")
            }
        }
    }
}
