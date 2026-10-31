package org.seedatlas.xaero.integration;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

/**
 * Runs the Xaero compatibility check the way the game does, on the shipped artifacts rather than on
 * development classes.
 *
 * <p>{@link XaeroCompatibilityTest} checks the compiled classes, which Loom has remapped into the
 * named namespace. Inside a running game there is no such remapping: Seed Atlas and Xaero are both
 * intermediary, so every Minecraft type mentioned in a descriptor is {@code class_xxx} while Xaero's
 * own members keep their names. On an obfuscated release the two namespaces disagree, and a check
 * written against the named one reports the integration broken when it is fine; the mod then
 * disables itself in game while every development test still passes.
 *
 * <p>Both halves matter, and both are taken from the shipped jar:
 * <ul>
 *   <li>the checker class itself, loaded through its own class loader, because the released
 *       {@code XaeroCompatibility} is the code that actually decides at runtime; and</li>
 *   <li>Seed Atlas' own classes, read as bytes from the same jar.</li>
 * </ul>
 * Xaero's side comes from the original jar the dependency resolved from, since that is what the
 * game loads — Loom's remapped copy exists only for the development class path.
 *
 * <p>{@code -PshippedJar=<jar>} points the test at an already published artifact, which is how the
 * regression that prompted this test was confirmed to be detectable.
 */
public final class ProductionCompatTest {
    private static final String CHECKER = "org.seedatlas.xaero.compat.XaeroCompatibility";
    private static final String CHECK = "check";

    public static void main(String[] args) throws Exception {
        Path shipped = Path.of(System.getProperty("seedatlas.shippedJar"));
        Path xaero = Path.of(System.getProperty("seedatlas.xaeroJar"));

        try (JarFile shippedJar = new JarFile(shipped.toFile());
             JarFile xaeroJar = new JarFile(xaero.toFile());
             JarFile xaeroLibJar = new JarFile(nestedXaeroLib(xaeroJar));
             URLClassLoader shippedLoader = checkerLoader(shipped)) {

            // The checker is loaded from the artifact so the released logic is the one under test.
            // The loader's parent deliberately cannot see Seed Atlas: class loading is parent-first,
            // so a parent that already had these classes would silently supply the test's copy and
            // the released logic would never run.
            Class<?> checkerClass = Class.forName(CHECKER, true, shippedLoader);
            Class<?> classSource = Class.forName(CHECKER + "$ClassSource", true, shippedLoader);

            Object source = java.lang.reflect.Proxy.newProxyInstance(
                shippedLoader,
                new Class<?>[] {classSource},
                (proxy, method, methodArgs) -> {
                    String name = (String) methodArgs[0];
                    try {
                        ClassNode node = from(shippedJar, name);
                        if (node != null) return node;
                        node = from(xaeroJar, name);
                        if (node != null) return node;
                        return from(xaeroLibJar, name);
                    } catch (IOException exception) {
                        throw new UncheckedIOException(exception);
                    }
                });

            Object report = checkerClass.getMethod(CHECK, classSource).invoke(null, source);
            var reportClass = report.getClass();
            boolean compatible = (boolean) reportClass.getMethod("compatible").invoke(report);
            var problems = (java.util.List<?>) reportClass.getMethod("problems").invoke(report);
            var warnings = (java.util.List<?>) reportClass.getMethod("warnings").invoke(report);
            int checked = ((java.util.Set<?>) reportClass.getMethod("checked").invoke(report)).size();

            problems.forEach(problem -> System.out.println("Incompatible: " + problem));
            warnings.forEach(warning -> System.out.println("Optional feature lost: " + warning));
            if (!compatible) {
                throw new AssertionError("The shipped jar is not compatible with the installed Xaero's "
                    + "World Map. Inside the game both are intermediary, so this is what the mixin "
                    + "plugin would see; Seed Atlas would disable itself. " + problems.size()
                    + " problem(s) reported by the checker in " + shipped.getFileName() + ".");
            }
            System.out.println("Shipped jar " + shipped.getFileName() + " is compatible with "
                + xaero.getFileName() + " in the intermediary namespace (" + checked + " classes)");
        } catch (InvocationTargetException exception) {
            throw new AssertionError("The compatibility check inside " + shipped.getFileName()
                + " failed to run", exception.getCause());
        }
    }

    /**
     * A loader for the checker that hides Seed Atlas from its parent, so the classes really come
     * from the artifact. ASM and the JDK stay visible, since the released jar expects them from the
     * environment just as it does in game.
     */
    private static URLClassLoader checkerLoader(Path shipped) throws IOException {
        ClassLoader hideSeedAtlas = new ClassLoader(ProductionCompatTest.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("org.seedatlas.xaero.")) {
                    throw new ClassNotFoundException(name);
                }
                return super.loadClass(name, resolve);
            }
        };
        return new URLClassLoader(new URL[] {shipped.toUri().toURL()}, hideSeedAtlas);
    }

    /** Xaero's World Map bundles XaeroLib as a nested jar. */
    private static java.io.File nestedXaeroLib(JarFile xaeroJar) throws IOException {
        var entry = xaeroJar.stream()
            .filter(candidate -> candidate.getName().startsWith("META-INF/jars/")
                && candidate.getName().contains("xaerolib")
                && candidate.getName().endsWith(".jar"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No nested XaeroLib jar inside " + xaeroJar.getName()));
        Path temp = Files.createTempFile("xaerolib", ".jar");
        temp.toFile().deleteOnExit();
        try (InputStream input = xaeroJar.getInputStream(entry)) {
            Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING);
        }
        return temp.toFile();
    }

    private static ClassNode from(JarFile jar, String internalName) throws IOException {
        var entry = jar.getEntry(internalName + ".class");
        if (entry == null) return null;
        try (InputStream input = jar.getInputStream(entry)) {
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            return node;
        }
    }

    private ProductionCompatTest() {
    }
}
