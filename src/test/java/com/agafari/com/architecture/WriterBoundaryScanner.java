package com.agafari.com.architecture;

import com.agafari.com.jpa.entity.BusinessSubscription;
import com.agafari.com.jpa.repository.BusinessSubscriptionRepository;
import org.springframework.asm.AnnotationVisitor;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.FieldVisitor;
import org.springframework.asm.Handle;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.asm.SpringAsmInfo;
import org.springframework.asm.Type;
import org.springframework.data.jpa.repository.Modifying;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * AGF SUB-R2-05: reads compiled classes and reports writes to subscriptions outside the single writer. Rules:
 * <ul>
 *   <li>CREATE: {@code new BusinessSubscription} or its static factories;</li>
 *   <li>MUTATE: its state-changing methods or a direct field write, also through a lambda or method reference;</li>
 *   <li>REPOSITORY: {@code BusinessSubscriptionRepository} save, delete or {@code @Modifying} methods;</li>
 *   <li>SQL: an insert, update or delete on {@code business_subscription} or {@code subscription_period}, in a string, a
 *   concatenation recipe or a {@code @Query}, in any class except that repository;</li>
 *   <li>PERSISTENCE: {@code EntityManager} persist, merge or remove, or reflective field writes, in a class that refers to
 *   the entity;</li>
 *   <li>VERIFIED_PAYMENT and PROVIDER_SDK: the story's two rules for later billing code;</li>
 *   <li>LOCK ({@link #lockViolations}): inside the writer, each write follows a lock check.</li>
 * </ul>
 * Reads are never reported. Limits: a statement assembled at run time from separate variables, and reflection through a class
 * that never names the entity, can't be seen in bytecode; the database triggers remain the last line of defence.
 */
final class WriterBoundaryScanner {

    record Violation(String rule, String className, String method, String detail) {
        @Override
        public String toString() {
            return "[" + rule + "] " + className + "#" + method + ": " + detail;
        }
    }

    private static final String ENTITY = Type.getInternalName(BusinessSubscription.class);
    private static final String REPOSITORY = Type.getInternalName(BusinessSubscriptionRepository.class);
    private static final String LIFECYCLE = "com/agafari/com/subscription/SubscriptionLifecycle";
    private static final String LOCKS = "com/agafari/com/service/MonetizationLocks";
    private static final String ENTITY_MANAGER = "jakarta/persistence/EntityManager";
    private static final String FIELD = "java/lang/reflect/Field";
    private static final String PAYMENT_OUTCOME_SERVICE = "com/agafari/com/billing/PaymentOutcomeService";
    private static final String STRIPE_SDK = "com/stripe/";
    private static final List<String> PROVIDER_ADAPTERS = List.of("com/agafari/com/billing/stripe/", "com/agafari/com/billing/chapa/");
    private static final Pattern WRITE_SQL = Pattern.compile("(?is)\\b(insert\\s+into|update|delete\\s+from)\\s+(business_subscription|subscription_period|BusinessSubscription)\\b");

    private static final Set<String> MUTATORS = Arrays.stream(BusinessSubscription.class.getDeclaredMethods())
            .filter(m -> Modifier.isPublic(m.getModifiers()) && !Modifier.isStatic(m.getModifiers()) && m.getReturnType() == void.class)
            .map(Method::getName).collect(Collectors.toSet());
    private static final Set<String> FACTORIES = Arrays.stream(BusinessSubscription.class.getDeclaredMethods())
            .filter(m -> Modifier.isPublic(m.getModifiers()) && Modifier.isStatic(m.getModifiers()) && m.getReturnType() == BusinessSubscription.class)
            .map(Method::getName).collect(Collectors.toSet());
    private static final Set<String> REPOSITORY_WRITES = Arrays.stream(BusinessSubscriptionRepository.class.getDeclaredMethods())
            .filter(m -> m.isAnnotationPresent(Modifying.class) || m.getName().matches("(save|delete|remove).*"))
            .map(Method::getName).collect(Collectors.toSet());

    private WriterBoundaryScanner() {
    }

    static List<Violation> scanTree(Path classesRoot) {
        List<Violation> all = new ArrayList<>();
        for (Path file : classFiles(classesRoot)) {
            try (InputStream in = Files.newInputStream(file)) {
                all.addAll(scanBytes(in.readAllBytes()));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return all;
    }

    static int classesScanned(Path classesRoot) {
        return classFiles(classesRoot).size();
    }

    private static List<Path> classFiles(Path root) {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(p -> p.toString().endsWith(".class")).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<Violation> scanClass(Class<?> type) {
        return scanBytes(bytes(type));
    }

    private static byte[] bytes(Class<?> type) {
        try (InputStream in = type.getClassLoader().getResourceAsStream(type.getName().replace('.', '/') + ".class")) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<Violation> scanBytes(byte[] classFile) {
        BoundaryVisitor visitor = new BoundaryVisitor();
        new ClassReader(classFile).accept(visitor, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return visitor.result();
    }

    /** Inside the writer: every method that writes checks a lock first; only {@code restaurantOnlyMethod} may use the narrower check. */
    static List<Violation> lockViolations(Class<?> lifecycle, String restaurantOnlyMethod) {
        String name = Type.getInternalName(lifecycle);
        List<Violation> violations = new ArrayList<>();
        new ClassReader(bytes(lifecycle)).accept(new ClassVisitor(SpringAsmInfo.ASM_VERSION) {
            @Override
            public MethodVisitor visitMethod(int access, String method, String descriptor, String signature, String[] exceptions) {
                return new MethodVisitor(SpringAsmInfo.ASM_VERSION) {
                    boolean locked;
                    boolean reported;

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String called, String desc, boolean itf) {
                        if (owner.equals(LOCKS) && called.equals("assertHeld")) locked = true;
                        if (owner.equals(LOCKS) && called.equals("assertRestaurantHeld")) {
                            locked = true;
                            if (!method.equals(restaurantOnlyMethod)) violations.add(new Violation("LOCK", name, method, "only " + restaurantOnlyMethod + " may use the restaurant-only lock check"));
                        }
                        boolean write = (isEntity(owner) && MUTATORS.contains(called)) || (isRepository(owner) && REPOSITORY_WRITES.contains(called));
                        if (write && !locked && !reported) {
                            reported = true;
                            violations.add(new Violation("LOCK", name, method, called + " runs before any lock check"));
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return violations;
    }

    private static boolean isEntity(String owner) {
        return owner.equals(ENTITY) || assignable(BusinessSubscription.class, owner);
    }

    private static boolean isRepository(String owner) {
        return owner.equals(REPOSITORY) || assignable(BusinessSubscriptionRepository.class, owner);
    }

    private static final Map<String, Class<?>> LOADED = new java.util.concurrent.ConcurrentHashMap<>();

    private static boolean assignable(Class<?> target, String owner) {
        if (!owner.startsWith("com/agafari/")) return false;
        Class<?> type = LOADED.computeIfAbsent(owner, o -> {
            try {
                return Class.forName(o.replace('/', '.'), false, WriterBoundaryScanner.class.getClassLoader());
            } catch (ClassNotFoundException | LinkageError e) {
                return Void.class;
            }
        });
        return target.isAssignableFrom(type);
    }

    private static final class BoundaryVisitor extends ClassVisitor {
        private String name;
        private final List<Violation> direct = new ArrayList<>();
        private final Map<String, Violation> persistence = new LinkedHashMap<>();
        private final Set<String> seen = new HashSet<>();
        private boolean refersToEntity;

        BoundaryVisitor() {
            super(SpringAsmInfo.ASM_VERSION);
        }

        List<Violation> result() {
            List<Violation> all = new ArrayList<>(direct);
            if (refersToEntity) all.addAll(persistence.values());
            return all;
        }

        private boolean writerSide() {
            return name.equals(LIFECYCLE) || name.startsWith(LIFECYCLE + "$") || name.equals(ENTITY) || name.startsWith(ENTITY + "$");
        }

        private void report(String rule, String method, String detail) {
            if (seen.add(rule + method + detail)) direct.add(new Violation(rule, name, method, detail));
        }

        private void noteTypes(String descriptor) {
            if (descriptor == null) return;
            if (descriptor.contains(ENTITY) || descriptor.contains(REPOSITORY)) refersToEntity = true;
            if (descriptor.contains(STRIPE_SDK)) provider("<declaration>", descriptor);
        }

        private void provider(String method, String reference) {
            if (PROVIDER_ADAPTERS.stream().noneMatch(name::startsWith)) report("PROVIDER_SDK", method, "provider SDK type outside its adapter: " + reference);
        }

        private void sql(String method, String text) {
            if (!name.equals(REPOSITORY) && WRITE_SQL.matcher(text).find()) report("SQL", method, "direct write statement on a subscription table");
        }

        private void called(String method, String owner, String target, String descriptor) {
            noteTypes(owner);
            noteTypes(descriptor);
            if (owner.startsWith(STRIPE_SDK)) provider(method, owner);
            if (!writerSide()) {
                if (isEntity(owner) && target.equals("<init>")) report("CREATE", method, "new BusinessSubscription");
                if (isEntity(owner) && FACTORIES.contains(target)) report("CREATE", method, "BusinessSubscription." + target);
                if (isEntity(owner) && MUTATORS.contains(target)) report("MUTATE", method, "BusinessSubscription." + target);
                if (isRepository(owner) && REPOSITORY_WRITES.contains(target)) report("REPOSITORY", method, "BusinessSubscriptionRepository." + target);
                if (owner.equals(ENTITY_MANAGER) && target.matches("persist|merge|remove")) persistence.putIfAbsent(method + target, new Violation("PERSISTENCE", name, method, "EntityManager." + target));
                if (owner.equals(FIELD) && target.matches("set(Int|Long|Boolean|Byte|Short|Char|Float|Double)?")) persistence.putIfAbsent(method + target, new Violation("PERSISTENCE", name, method, "reflective field write"));
            }
            if (target.equals("<init>") && owner.endsWith("/VerifiedPaymentRef") && !name.equals(PAYMENT_OUTCOME_SERVICE)) {
                report("VERIFIED_PAYMENT", method, "only PaymentOutcomeService constructs VerifiedPaymentRef");
            }
        }

        private AnnotationVisitor annotation(String method) {
            return new AnnotationVisitor(SpringAsmInfo.ASM_VERSION) {
                @Override
                public void visit(String key, Object value) {
                    if (value instanceof String text) sql(method, text);
                }

                @Override
                public AnnotationVisitor visitAnnotation(String key, String descriptor) {
                    return this;
                }

                @Override
                public AnnotationVisitor visitArray(String key) {
                    return this;
                }
            };
        }

        @Override
        public void visit(int version, int access, String className, String signature, String superName, String[] interfaces) {
            name = className;
            noteTypes(superName);
            noteTypes(signature);
            if (interfaces != null) for (String i : interfaces) noteTypes(i);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            return annotation("<class>");
        }

        @Override
        public FieldVisitor visitField(int access, String field, String descriptor, String signature, Object value) {
            noteTypes(descriptor);
            noteTypes(signature);
            if (value instanceof String text) sql("<field " + field + ">", text);
            return null;
        }

        @Override
        public MethodVisitor visitMethod(int access, String method, String descriptor, String signature, String[] exceptions) {
            noteTypes(descriptor);
            noteTypes(signature);
            return new MethodVisitor(SpringAsmInfo.ASM_VERSION) {
                @Override
                public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                    return BoundaryVisitor.this.annotation(method);
                }

                @Override
                public void visitMethodInsn(int opcode, String owner, String target, String desc, boolean itf) {
                    called(method, owner, target, desc);
                }

                @Override
                public void visitFieldInsn(int opcode, String owner, String field, String desc) {
                    noteTypes(owner);
                    if (owner.startsWith(STRIPE_SDK)) provider(method, owner);
                    if ((opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC) && !writerSide() && isEntity(owner)) report("MUTATE", method, "field write " + field);
                }

                @Override
                public void visitTypeInsn(int opcode, String type) {
                    noteTypes(type);
                    if (type.startsWith(STRIPE_SDK)) provider(method, type);
                }

                @Override
                public void visitLdcInsn(Object value) {
                    if (value instanceof String text) sql(method, text);
                    if (value instanceof Type type) noteTypes(type.getDescriptor());
                }

                @Override
                public void visitInvokeDynamicInsn(String indyName, String desc, Handle bootstrap, Object... arguments) {
                    noteTypes(desc);
                    for (Object argument : arguments) {
                        if (argument instanceof Handle h) called(method, h.getOwner(), h.getName(), h.getDesc());
                        if (argument instanceof String text) sql(method, text);
                    }
                }
            };
        }
    }
}
