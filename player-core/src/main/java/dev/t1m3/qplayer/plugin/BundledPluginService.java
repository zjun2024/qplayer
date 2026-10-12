package dev.t1m3.qplayer.plugin;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dev.t1m3.qplayer.store.AppDirs;
import dev.t1m3.qplayer.store.StorageFiles;
import dev.t1m3.qplayer.util.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Installs the {@code .qplug} packages that ship inside the host binary, so a
 * build that bundles source plugins has them active on first launch and after
 * every app update — the user never reinstalls a plugin by hand.
 *
 * <p>Bundled packages live in this class's own package directory as classpath
 * resources ({@code dev/t1m3/qplayer/plugin/bundled/}), which both the Android
 * APK (resources merged from the player-core jar) and the desktop jar carry.
 * {@code index.json} lists them as {@code <id>-<version>.qplug}; the id and
 * version in the file name are cross-checked against the verified manifest, so
 * a misnamed resource cannot install something unexpected.
 *
 * <p>Installation goes through the same verifier, staged installer and registry
 * as a user-initiated install, so integrity receipts, permission grants and
 * activation behave identically. Packages signed by a publisher key pinned in
 * the plugin catalog are verified against that key; anything else is accepted
 * through the same allow-unsigned path the manual import uses, because a fork
 * that rebuilds and re-signs its own copy of an official package must keep
 * working.
 *
 * <p>A small marker file ({@code config/bundled-plugins.json}) records which
 * bundle version was last handled per plugin id, so a steady-state launch only
 * reads {@code index.json} and the marker — never the package bytes. A bundled
 * package never downgrades a newer manually installed copy: when the installed
 * version is equal or newer the bundle is only recorded as handled.
 */
public final class BundledPluginService {
    private static final String INDEX_RESOURCE = "index.json";
    private static final String RESOURCE_PREFIX = "bundled/";
    private static final Type STRING_MAP =
            new TypeToken<LinkedHashMap<String, String>>() {}.getType();

    private final PluginPackageVerifier verifier;
    private final PluginManager manager;
    private final PluginInstaller installer;
    private final PluginRegistry registry;
    private final PluginCatalogService catalog;
    private final Gson gson = new Gson();

    public BundledPluginService(PluginPackageVerifier verifier,
                                PluginManager manager,
                                PluginInstaller installer,
                                PluginRegistry registry,
                                PluginCatalogService catalog) {
        if (verifier == null || manager == null || installer == null || registry == null) {
            throw new IllegalArgumentException();
        }
        this.verifier = verifier;
        this.manager = manager;
        this.installer = installer;
        this.registry = registry;
        this.catalog = catalog;
    }

    /**
     * Brings every bundled package up to date in the plugin registry. Runs on
     * the caller's thread before {@link PluginManager#startEnabled()}, so an
     * install finished here starts in the normal startup pass. Idempotent: a
     * second call in the same process, or a launch where nothing changed,
     * touches no package bytes. Never throws — a broken bundle logs and leaves
     * the app running as a local player instead of blocking startup.
     */
    public void installMissing() {
        // Unit tests build a real PlayerController on the same classpath that
        // carries these resources; installing live source plugins there would
        // fetch real network data into fixture-driven assertions.
        if (Boolean.getBoolean("qplayer.skipBundledPlugins")) return;
        List<String> packages;
        try {
            packages = readIndex();
        } catch (Throwable error) {
            Logger.warn("bundled plugin index unreadable: {}", safeMessage(error));
            return;
        }
        if (packages.isEmpty()) return;
        Map<String, String> handled;
        try {
            handled = readHandledMarker();
        } catch (Throwable error) {
            handled = new LinkedHashMap<>();
        }
        boolean markerChanged = false;
        for (String name : packages) {
            try {
                if (installOne(name, handled)) markerChanged = true;
            } catch (Throwable error) {
                Logger.warn("bundled plugin {} not installed: {}",
                        name, safeMessage(error));
            }
        }
        if (markerChanged) {
            try {
                StorageFiles.writeUtf8Atomic(markerFile(), gson.toJson(handled));
            } catch (Throwable error) {
                Logger.warn("bundled plugin marker not saved: {}", safeMessage(error));
            }
        }
    }

    /** Returns true when the marker file must be rewritten. */
    private boolean installOne(String resourceName, Map<String, String> handled)
            throws IOException, GeneralSecurityException {
        String id = bundledId(resourceName);
        String version = bundledVersion(resourceName);

        PluginRegistry.Entry installed = registry.get(id);
        if (installed != null) {
            int relation = PluginCompatibility.compare(version, installed.activeVersion);
            if (version.equals(handled.get(id)) && relation <= 0) {
                return false; // this exact bundle was already accounted for
            }
            if (relation <= 0) {
                // Installed copy is the same or newer: never downgrade, just
                // record the bundle so later launches skip it.
                handled.put(id, version);
                return true;
            }
        } else if (version.equals(handled.get(id))) {
            // Marker survived but the plugin is gone (user removed it): reinstall.
            handled.remove(id);
        }

        Path staged = AppDirs.cacheDir().resolve("bundled-plugins").resolve(resourceName);
        Files.createDirectories(staged.getParent());
        try {
            try (InputStream in = openResource(resourceName)) {
                if (in == null) {
                    throw new IOException("bundled plugin resource is missing: " + resourceName);
                }
                Files.copy(in, staged, StandardCopyOption.REPLACE_EXISTING);
            }
            VerifiedPluginPackage pkg = verify(staged, id, version);
            manager.installAndEnable(installer, pkg, pkg.manifest().permissionSet());
            Logger.info("installed bundled plugin {} {}", pkg.manifest().id,
                    pkg.manifest().version);
            handled.put(id, version);
            return true;
        } finally {
            try { Files.deleteIfExists(staged); }
            catch (IOException ignored) {}
        }
    }

    private VerifiedPluginPackage verify(Path file, String expectedId, String expectedVersion)
            throws IOException, GeneralSecurityException {
        PublicKey key = publisherKey(expectedId);
        VerifiedPluginPackage pkg;
        if (key != null) {
            try {
                pkg = verifier.verify(file, key, false);
            } catch (GeneralSecurityException signatureMismatch) {
                // A fork may rebuild and re-sign an official package; fall back
                // to the same allow-unsigned path a manual import uses.
                Logger.warn("bundled plugin {} failed its pinned-key check ({}), "
                                + "installing as unsigned", expectedId,
                        safeMessage(signatureMismatch));
                pkg = verifier.verify(file, null, true);
            }
        } else {
            pkg = verifier.verify(file, null, true);
        }
        // The file name is this service's own index, not part of the signed
        // package: cross-check it so a misfiled resource cannot install a
        // different plugin than the marker claims.
        if (!expectedId.equals(pkg.manifest().id)
                || !expectedVersion.equals(pkg.manifest().version)) {
            throw new IOException("bundled plugin file name does not match its manifest: "
                    + expectedId + "-" + expectedVersion + " declares "
                    + pkg.manifest().id + "-" + pkg.manifest().version);
        }
        return pkg;
    }

    private PublicKey publisherKey(String pluginId) {
        if (catalog == null) return null;
        try {
            return catalog.publisherKeyFor(pluginId);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static List<String> readIndex() throws IOException {
        try (InputStream in = openResource(INDEX_RESOURCE)) {
            if (in == null) return Collections.emptyList();
            String json = new String(readAll(in), StandardCharsets.UTF_8);
            List<String> names = new Gson().fromJson(json, List.class);
            List<String> out = new ArrayList<>();
            if (names != null) {
                for (Object name : names) {
                    if (name instanceof String && !((String) name).trim().isEmpty()) {
                        out.add(((String) name).trim());
                    }
                }
            }
            return out;
        }
    }

    private Map<String, String> readHandledMarker() throws IOException {
        Path file = markerFile();
        if (!Files.isRegularFile(file)) return new LinkedHashMap<>();
        try {
            Map<String, String> handled = gson.fromJson(
                    StorageFiles.readUtf8(file), STRING_MAP);
            return handled == null ? new LinkedHashMap<>() : handled;
        } catch (RuntimeException corrupted) {
            return new LinkedHashMap<>(); // rebuild the marker on the next install
        }
    }

    private static Path markerFile() {
        return AppDirs.configFile("bundled-plugins.json");
    }

    private static InputStream openResource(String name) {
        return BundledPluginService.class.getResourceAsStream(RESOURCE_PREFIX + name);
    }

    /** {@code netease-0.5.3.qplug} -> {@code netease}; the last dash separates
     *  the version, so plugin ids may themselves contain dashes. */
    private static String bundledId(String resourceName) {
        String base = trimSuffix(resourceName);
        int dash = base.lastIndexOf('-');
        if (dash <= 0 || dash == base.length() - 1) {
            throw new IllegalArgumentException(
                    "bundled plugin must be named <id>-<version>.qplug: " + resourceName);
        }
        return base.substring(0, dash);
    }

    private static String bundledVersion(String resourceName) {
        String base = trimSuffix(resourceName);
        return base.substring(base.lastIndexOf('-') + 1);
    }

    private static String trimSuffix(String name) {
        return name.endsWith(".qplug") ? name.substring(0, name.length() - 6) : name;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = in.read(buffer)) >= 0) out.write(buffer, 0, count);
        return out.toByteArray();
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }
}
