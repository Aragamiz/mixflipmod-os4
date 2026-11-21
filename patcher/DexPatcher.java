import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.iface.Field;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.StringReference;
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;
import com.android.tools.smali.dexlib2.immutable.ImmutableField;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference;
import com.android.tools.smali.dexlib2.immutable.value.ImmutableStringEncodedValue;
import com.android.tools.smali.dexlib2.writer.io.FileDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 使用 dexlib2 在 cn.zeldati.mixflipmod 原包 classes.dex 中注入两处轻量回调：
 * 1. MainActivity.onCreate(Bundle) 开头 -> ExtraPrefsUI.onMainActivityCreate(this)
 * 2. ix1.d(..., SharedPreferences, ...) 开头 -> ExtraPrefsUI.ensureDefaults(sp)
 */
public final class DexPatcher {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: DexPatcher <in.dex> <out.dex>");
            System.exit(1);
        }
        File inFile = new File(args[0]);
        File outFile = new File(args[1]);
        DexFile dexFile = DexFileFactory.loadDexFile(inFile, Opcodes.forApi(33));

        boolean patchedMain = false;
        boolean patchedIx1 = false;
        int replacedVer = 0;

        DexPool pool = new DexPool(Opcodes.forApi(33));
        for (ClassDef cd : dexFile.getClasses()) {
            String type = cd.getType();
            boolean classModified = false;

            List<Field> newFields = new ArrayList<>();
            for (Field f : cd.getFields()) {
                if (f.getInitialValue() instanceof StringEncodedValue) {
                    String val = ((StringEncodedValue) f.getInitialValue()).getValue();
                    if ("1.0.8-flip2".equals(val)) {
                        newFields.add(new ImmutableField(
                                f.getDefiningClass(), f.getName(), f.getType(), f.getAccessFlags(),
                                new ImmutableStringEncodedValue("1.0.13-flip2(re)"),
                                f.getAnnotations(), f.getHiddenApiRestrictions()));
                        classModified = true;
                        replacedVer++;
                        continue;
                    }
                }
                newFields.add(f);
            }

            List<Method> newMethods = new ArrayList<>();
            for (Method m : cd.getMethods()) {
                MethodImplementation impl = m.getImplementation();
                if (impl != null && "Lcom/parallelc/mixflipmod/ui/MainActivity;".equals(type)
                        && "onCreate".equals(m.getName())) {
                    int p0 = impl.getRegisterCount() - 2; // this, Bundle
                    MutableMethodImplementation mut = new MutableMethodImplementation(impl);
                    ImmutableMethodReference ref = new ImmutableMethodReference(
                            "Lcom/collasong/flipextra/ExtraPrefsUI;",
                            "onMainActivityCreate",
                            Collections.singletonList("Landroid/app/Activity;"),
                            "V"
                    );
                    mut.addInstruction(0, new BuilderInstruction3rc(
                            Opcode.INVOKE_STATIC_RANGE, p0, 1, ref));
                    patchedMain = true;
                    classModified = true;
                    newMethods.add(new ImmutableMethod(
                            m.getDefiningClass(), m.getName(), m.getParameters(),
                            m.getReturnType(), m.getAccessFlags(), m.getAnnotations(),
                            m.getHiddenApiRestrictions(), mut));
                } else if (impl != null && "Lix1;".equals(type) && "d".equals(m.getName())
                        && m.getParameterTypes().size() == 5
                        && "Landroid/content/SharedPreferences;".equals(
                                m.getParameterTypes().get(1).toString())) {
                    int p1 = impl.getRegisterCount() - 4; // 5 static args -> p0..p4
                    MutableMethodImplementation mut = new MutableMethodImplementation(impl);
                    ImmutableMethodReference ref = new ImmutableMethodReference(
                            "Lcom/collasong/flipextra/ExtraPrefsUI;",
                            "ensureDefaults",
                            Collections.singletonList("Landroid/content/SharedPreferences;"),
                            "V"
                    );
                    mut.addInstruction(0, new BuilderInstruction3rc(
                            Opcode.INVOKE_STATIC_RANGE, p1, 1, ref));
                    patchedIx1 = true;
                    classModified = true;
                    newMethods.add(new ImmutableMethod(
                            m.getDefiningClass(), m.getName(), m.getParameters(),
                            m.getReturnType(), m.getAccessFlags(), m.getAnnotations(),
                            m.getHiddenApiRestrictions(), mut));
                } else if (impl != null) {
                    boolean methodMod = false;
                    MutableMethodImplementation mut = null;
                    int idx = 0;
                    for (Instruction inst : impl.getInstructions()) {
                        if (inst instanceof ReferenceInstruction
                                && ((ReferenceInstruction) inst).getReference() instanceof StringReference) {
                            String s = ((StringReference) ((ReferenceInstruction) inst).getReference()).getString();
                            if ("1.0.8-flip2".equals(s) && inst.getOpcode() == Opcode.CONST_STRING) {
                                if (mut == null) {
                                    mut = new MutableMethodImplementation(impl);
                                }
                                int reg = ((com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction) inst).getRegisterA();
                                mut.replaceInstruction(idx, new BuilderInstruction21c(
                                        Opcode.CONST_STRING, reg, new ImmutableStringReference("1.0.13-flip2(re)")));
                                methodMod = true;
                                replacedVer++;
                            }
                        }
                        idx++;
                    }
                    if (methodMod) {
                        classModified = true;
                        newMethods.add(new ImmutableMethod(
                                m.getDefiningClass(), m.getName(), m.getParameters(),
                                m.getReturnType(), m.getAccessFlags(), m.getAnnotations(),
                                m.getHiddenApiRestrictions(), mut));
                    } else {
                        newMethods.add(m);
                    }
                } else {
                    newMethods.add(m);
                }
            }

            if (classModified) {
                cd = new ImmutableClassDef(
                        cd.getType(), cd.getAccessFlags(), cd.getSuperclass(),
                        cd.getInterfaces(), cd.getSourceFile(), cd.getAnnotations(),
                        newFields, newMethods);
            }
            pool.internClass(cd);
        }

        if (!patchedMain || !patchedIx1) {
            throw new IllegalStateException("Patch incomplete: main=" + patchedMain + " ix1=" + patchedIx1);
        }
        pool.writeTo(new FileDataStore(outFile));
        System.out.println("DEX_PATCH_OK: MainActivity.onCreate + ix1.d (verStrings=" + replacedVer + ")");
    }
}
