package com.agafari.com.architecture;

import com.agafari.com.billing.stripe.StripeProviderClient;
import org.junit.jupiter.api.Test;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.FieldVisitor;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AGF SUB-R5-01, AC2 (BD-13): Stripe SDK types stay in {@code billing.stripe}. Checked on the compiled production classes:
 * <ol>
 *   <li>no class outside {@code com/agafari/com/billing/stripe/} refers to {@code com/stripe/} anywhere in its class file
 *   (calls, fields, signatures, annotations, constants);</li>
 *   <li>inside the package, no public or protected member exposes an SDK type, so none can escape through the adapter's API;</li>
 *   <li>the dependency is the PO-approved pin, and the SDK reports the expected release and API version.</li>
 * </ol>
 */
class StripeSdkBoundaryTest {

    private static final String ADAPTER = "com/agafari/com/billing/stripe/";
    private static final String SDK = "com/stripe/";

    private static Path productionClasses() throws Exception {
        return Path.of(StripeProviderClient.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    private static List<Path> classFiles() throws Exception {
        Path root = productionClasses();
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(p -> p.toString().endsWith(".class")).toList();
        }
    }

    private static String internalName(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/').replaceAll("\\.class$", "");
    }

    /** Every reference to an SDK type in a class file, by byte content (constant pool), outside the adapter package. */
    static List<String> sdkReferencesOutsideTheAdapter(Path root, List<Path> files) throws Exception {
        List<String> found = new ArrayList<>();
        for (Path f : files) {
            String name = internalName(root, f);
            if (name.startsWith(ADAPTER)) continue;
            String bytes = new String(Files.readAllBytes(f), StandardCharsets.ISO_8859_1);
            if (bytes.contains(SDK)) found.add(name);
        }
        return found;
    }

    /** Public or protected members of adapter classes whose descriptor or generic signature names an SDK type. */
    static List<String> sdkTypesInTheAdaptersApi(List<byte[]> adapterClasses) {
        List<String> found = new ArrayList<>();
        for (byte[] bytes : adapterClasses) {
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                private String owner;
                private boolean exported;

                @Override
                public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                    owner = name;
                    exported = (access & Opcodes.ACC_PUBLIC) != 0;
                    if (exported && superName != null && superName.startsWith(SDK)) found.add(name + " extends " + superName);
                    if (exported && interfaces != null) for (String i : interfaces) if (i.startsWith(SDK)) found.add(name + " implements " + i);
                }

                @Override
                public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                    if (exported && visible(access) && names(descriptor, signature)) found.add(owner + "." + name + " : " + descriptor);
                    return null;
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    if (exported && visible(access) && names(descriptor, signature)) found.add(owner + "#" + name + descriptor);
                    return null;
                }
            }, ClassReader.SKIP_CODE);
        }
        return found;
    }

    private static boolean visible(int access) {
        return (access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0 && (access & Opcodes.ACC_SYNTHETIC) == 0;
    }

    private static boolean names(String descriptor, String signature) {
        return descriptor.contains("L" + SDK) || (signature != null && signature.contains("L" + SDK));
    }

    @Test
    void noProductionClassOutsideTheAdapterRefersToTheStripeSdk() throws Exception {
        List<Path> files = classFiles();
        assertThat(files).as("the scan sees the production classes").hasSizeGreaterThan(100);
        assertThat(files.stream().map(f -> internalName(productionClassesUnchecked(), f))).as("the adapter itself is scanned too").anyMatch(n -> n.startsWith(ADAPTER));
        assertThat(sdkReferencesOutsideTheAdapter(productionClasses(), files)).isEmpty();
    }

    @Test
    void theAdapterExposesNoSdkTypeInItsPublicOrProtectedApi() throws Exception {
        Path root = productionClasses();
        List<byte[]> adapter = new ArrayList<>();
        for (Path f : classFiles()) if (internalName(root, f).startsWith(ADAPTER)) adapter.add(Files.readAllBytes(f));
        assertThat(adapter).hasSizeGreaterThanOrEqualTo(4);
        assertThat(sdkTypesInTheAdaptersApi(adapter)).isEmpty();
    }

    @Test
    void theDependencyIsTheApprovedPinAndReportsItsApiVersion() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));
        assertThat(pom).contains("<groupId>com.stripe</groupId>\n            <artifactId>stripe-java</artifactId>\n            <version>34.1.0</version>");
        assertThat(pom).doesNotContainPattern("stripe-java</artifactId>\\s*<version>[^<]*(SNAPSHOT|beta|alpha|rc|\\[|\\(|LATEST|RELEASE)");
        assertThat(StripeProviderClient.sdkVersion()).isEqualTo("34.1.0");
        assertThat(StripeProviderClient.apiVersion()).as("the API version this SDK release pins").isEqualTo("2026-10-28.endive");
    }

    // ---- the scanners detect what they must (in-test controls, on synthetic bytecode) ----

    @Test
    void theReferenceScanReportsAnSdkReferenceOutsideTheAdapter() throws Exception {
        Path dir = Files.createTempDirectory("stripe-boundary");
        Path leaky = dir.resolve("com/agafari/com/service/LeakyService.class");
        Files.createDirectories(leaky.getParent());
        Files.write(leaky, classWithField("com/agafari/com/service/LeakyService", "Lcom/stripe/StripeClient;", Opcodes.ACC_PRIVATE));
        assertThat(sdkReferencesOutsideTheAdapter(dir, List.of(leaky))).containsExactly("com/agafari/com/service/LeakyService");
    }

    @Test
    void theApiScanReportsAPublicSdkTypeButNotAPrivateOne() {
        byte[] exposed = classWithField(ADAPTER + "Leaks", "Lcom/stripe/StripeClient;", Opcodes.ACC_PUBLIC);
        byte[] hidden = classWithField(ADAPTER + "Hides", "Lcom/stripe/StripeClient;", Opcodes.ACC_PRIVATE);
        assertThat(sdkTypesInTheAdaptersApi(List.of(exposed))).hasSize(1);
        assertThat(sdkTypesInTheAdaptersApi(List.of(hidden))).isEmpty();
    }

    private static byte[] classWithField(String internalName, String descriptor, int access) {
        org.springframework.asm.ClassWriter w = new org.springframework.asm.ClassWriter(0);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        w.visitField(access, "client", descriptor, null, null).visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    private static Path productionClassesUnchecked() {
        try {
            return productionClasses();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
