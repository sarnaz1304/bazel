// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.android.desugar;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Helper that keeps track of which core library classes and methods we want to rewrite.
 */
class CoreLibrarySupport {

  private static final Object[] EMPTY_FRAME = new Object[0];

  private final CoreLibraryRewriter rewriter;
  private final ClassLoader targetLoader;
  /** Internal name prefixes that we want to move to a custom package. */
  private final ImmutableSet<String> renamedPrefixes;
  private final ImmutableSet<String> excludeFromEmulation;
  /** Internal names of interfaces whose default and static interface methods we'll emulate. */
  private final ImmutableSet<Class<?>> emulatedInterfaces;
  /** Map from {@code owner#name} core library members to their new owners. */
  private final ImmutableMap<String, String> memberMoves;
  private final GeneratedClassStore store;

  private final HashMap<String, ClassVisitor> dispatchHelpers = new HashMap<>();

  public CoreLibrarySupport(
      CoreLibraryRewriter rewriter,
      ClassLoader targetLoader,
      GeneratedClassStore store,
      List<String> renamedPrefixes,
      List<String> emulatedInterfaces,
      List<String> memberMoves,
      List<String> excludeFromEmulation) {
    this.rewriter = rewriter;
    this.targetLoader = targetLoader;
    this.store = store;
    checkArgument(
        renamedPrefixes.stream().allMatch(prefix -> prefix.startsWith("java/")), renamedPrefixes);
    this.renamedPrefixes = ImmutableSet.copyOf(renamedPrefixes);
    this.excludeFromEmulation = ImmutableSet.copyOf(excludeFromEmulation);

    ImmutableSet.Builder<Class<?>> classBuilder = ImmutableSet.builder();
    for (String itf : emulatedInterfaces) {
      checkArgument(itf.startsWith("java/util/"), itf);
      Class<?> clazz = loadFromInternal(rewriter.getPrefix() + itf);
      checkArgument(clazz.isInterface(), itf);
      classBuilder.add(clazz);
    }
    this.emulatedInterfaces = classBuilder.build();

    // We can call isRenamed and rename below b/c we initialized the necessary fields above
    ImmutableMap.Builder<String, String> movesBuilder = ImmutableMap.builder();
    Splitter splitter = Splitter.on("->").trimResults().omitEmptyStrings();
    for (String move : memberMoves) {
      List<String> pair = splitter.splitToList(move);
      checkArgument(pair.size() == 2, "Doesn't split as expected: %s", move);
      checkArgument(pair.get(0).startsWith("java/"), "Unexpected member: %s", move);
      int sep = pair.get(0).indexOf('#');
      checkArgument(sep > 0 && sep == pair.get(0).lastIndexOf('#'), "invalid member: %s", move);
      checkArgument(!isRenamedCoreLibrary(pair.get(0).substring(0, sep)),
          "Original renamed, no need to move it: %s", move);
      checkArgument(isRenamedCoreLibrary(pair.get(1)), "Target not renamed: %s", move);
      checkArgument(!this.excludeFromEmulation.contains(pair.get(0)),
          "Retargeted invocation %s shouldn't overlap with excluded", move);

      movesBuilder.put(pair.get(0), renameCoreLibrary(pair.get(1)));
    }
    this.memberMoves = movesBuilder.build();
  }

  public boolean isRenamedCoreLibrary(String internalName) {
    String unprefixedName = rewriter.unprefix(internalName);
    if (!unprefixedName.startsWith("java/") || renamedPrefixes.isEmpty()) {
      return false; // shortcut
    }
    // Rename any classes desugar might generate under java/ (for emulated interfaces) as well as
    // configured prefixes
    return looksGenerated(unprefixedName)
        || renamedPrefixes.stream().anyMatch(prefix -> unprefixedName.startsWith(prefix));
  }

  public String renameCoreLibrary(String internalName) {
    internalName = rewriter.unprefix(internalName);
    return (internalName.startsWith("java/"))
        ? "j$/" + internalName.substring(/* cut away "java/" prefix */ 5)
        : internalName;
  }

  @Nullable
  public String getMoveTarget(String owner, String name) {
    return memberMoves.get(rewriter.unprefix(owner) + '#' + name);
  }

  /**
   * Returns {@code true} for java.* classes or interfaces that are subtypes of emulated interfaces.
   * Note that implies that this method always returns {@code false} for user-written classes.
   */
  public boolean isEmulatedCoreClassOrInterface(String internalName) {
    return getEmulatedCoreClassOrInterface(internalName) != null;
  }

  /** Includes the given method definition in any applicable core interface emulation logic. */
  public void registerIfEmulatedCoreInterface(
      int access,
      String owner,
      String name,
      String desc,
      String[] exceptions) {
    Class<?> emulated = getEmulatedCoreClassOrInterface(owner);
    if (emulated == null) {
      return;
    }
    checkArgument(emulated.isInterface(), "Shouldn't be called for a class: %s.%s", owner, name);
    checkArgument(
        BitFlags.noneSet(
            access,
            Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_STATIC | Opcodes.ACC_BRIDGE),
        "Should only be called for default methods: %s.%s", owner, name);

    ClassVisitor helper = dispatchHelper(owner);
    String companionDesc = InterfaceDesugaring.companionDefaultMethodDescriptor(owner, desc);
    MethodVisitor dispatchMethod =
        helper.visitMethod(
            access | Opcodes.ACC_STATIC,
            name,
            companionDesc,
            /*signature=*/ null,  // signature is invalid due to extra "receiver" argument
            exceptions);

    dispatchMethod.visitCode();
    {
      // See if the receiver might come with its own implementation of the method, and call it.
      // We do this by testing for the interface type created by EmulatedInterfaceRewriter
      Label callCompanion = new Label();
      String emulationInterface = renameCoreLibrary(owner);
      dispatchMethod.visitVarInsn(Opcodes.ALOAD, 0);  // load "receiver"
      dispatchMethod.visitTypeInsn(Opcodes.INSTANCEOF, emulationInterface);
      dispatchMethod.visitJumpInsn(Opcodes.IFEQ, callCompanion);
      dispatchMethod.visitVarInsn(Opcodes.ALOAD, 0);  // load "receiver"
      dispatchMethod.visitTypeInsn(Opcodes.CHECKCAST, emulationInterface);

      Type neededType = Type.getMethodType(desc);
      visitLoadArgs(dispatchMethod, neededType, 1 /* receiver already loaded above*/);
      dispatchMethod.visitMethodInsn(
          Opcodes.INVOKEINTERFACE,
          emulationInterface,
          name,
          desc,
          /*itf=*/ true);
      dispatchMethod.visitInsn(neededType.getReturnType().getOpcode(Opcodes.IRETURN));

      dispatchMethod.visitLabel(callCompanion);
      // Trivial frame for the branch target: same empty stack as before
      dispatchMethod.visitFrame(Opcodes.F_SAME, 0, EMPTY_FRAME, 0, EMPTY_FRAME);
    }

    // Call static type's default implementation in companion class
    Type neededType = Type.getMethodType(companionDesc);
    visitLoadArgs(dispatchMethod, neededType, 0);
    // TODO(b/70681189): Also test emulated subtypes and call their implementations before falling
    // back on static type's default implementation
    dispatchMethod.visitMethodInsn(
        Opcodes.INVOKESTATIC,
        InterfaceDesugaring.getCompanionClassName(owner),
        name,
        companionDesc,
        /*itf=*/ false);
    dispatchMethod.visitInsn(neededType.getReturnType().getOpcode(Opcodes.IRETURN));

    dispatchMethod.visitMaxs(0, 0);
    dispatchMethod.visitEnd();
  }

  /**
   * If the given invocation needs to go through a companion class of an emulated or renamed
   * core interface, this methods returns that interface.  This is a helper method for
   * {@link CoreLibraryInvocationRewriter}.
   *
   * <p>Always returns an interface (or {@code null}), even if {@code owner} is a class. Can only
   * return non-{@code null} if {@code owner} is a core library type.
   */
  @Nullable
  public Class<?> getCoreInterfaceRewritingTarget(
      int opcode, String owner, String name, String desc, boolean itf) {
    if (looksGenerated(owner)) {
      // Regular desugaring handles generated classes, no emulation is needed
      return null;
    }
    if (!itf && (opcode == Opcodes.INVOKESTATIC || opcode == Opcodes.INVOKESPECIAL)) {
      // Ignore staticly dispatched invocations on classes--they never need rewriting
      return null;
    }
    Class<?> clazz;
    if (isRenamedCoreLibrary(owner)) {
      // For renamed invocation targets we just need to do what InterfaceDesugaring does, that is,
      // only worry about invokestatic and invokespecial interface invocations; nothing to do for
      // invokevirtual and invokeinterface.  InterfaceDesugaring ignores bootclasspath interfaces,
      // so we have to do its work here for renamed interfaces.
      if (itf
          && (opcode == Opcodes.INVOKESTATIC || opcode == Opcodes.INVOKESPECIAL)) {
        clazz = loadFromInternal(owner);
      } else {
        return null;
      }
    } else {
      // If not renamed, see if the owner needs emulation.
      clazz = getEmulatedCoreClassOrInterface(owner);
      if (clazz == null) {
        return null;
      }
    }
    checkArgument(itf == clazz.isInterface(), "%s expected to be interface: %s", owner, itf);

    if (opcode == Opcodes.INVOKESTATIC) {
      // Static interface invocation always goes to the given owner
      checkState(itf); // we should've bailed out above.
      return clazz;
    }

    // See if the invoked method is a default method, which will need rewriting.  For invokespecial
    // we can only get here if its a default method, and invokestatic we handled above.
    Method callee = findInterfaceMethod(clazz, name, desc);
    if (callee != null && callee.isDefault()) {
      if (isExcluded(callee)) {
        return null;
      }
      Class<?> result = callee.getDeclaringClass();
      if (isRenamedCoreLibrary(result.getName().replace('.', '/'))
          || emulatedInterfaces.stream().anyMatch(emulated -> emulated.isAssignableFrom(result))) {
        return result;
      }
      // We get here if the declaring class is a supertype of an emulated interface.  In that case
      // use the emulated interface instead (since we don't desugar the supertype).  Fail in case
      // there are multiple possibilities.
      Iterator<Class<?>> roots =
          emulatedInterfaces
              .stream()
              .filter(
                  emulated -> emulated.isAssignableFrom(clazz) && result.isAssignableFrom(emulated))
              .iterator();
      checkState(roots.hasNext()); // must exist
      Class<?> substitute = roots.next();
      checkState(!roots.hasNext(), "Ambiguous emulation substitute: %s", callee);
      return substitute;
    } else {
      checkArgument(opcode != Opcodes.INVOKESPECIAL,
          "Couldn't resolve interface super call %s.super.%s : %s", owner, name, desc);
    }
    return null;
  }

  /**
   * Returns the given class if it's a core library class or interface with emulated default
   * methods.  This is equivalent to calling {@link #isEmulatedCoreClassOrInterface} and then
   * just loading the class (using the target class loader).
   */
  public Class<?> getEmulatedCoreClassOrInterface(String internalName) {
    if (looksGenerated(internalName)) {
      // Regular desugaring handles generated classes, no emulation is needed
      return null;
    }
    {
      String unprefixedOwner = rewriter.unprefix(internalName);
      if (!unprefixedOwner.startsWith("java/util/") || isRenamedCoreLibrary(unprefixedOwner)) {
        return null;
      }
    }

    Class<?> clazz = loadFromInternal(internalName);
    if (emulatedInterfaces.stream().anyMatch(itf -> itf.isAssignableFrom(clazz))) {
      return clazz;
    }
    return null;
  }

  private boolean isExcluded(Method method) {
    String unprefixedOwner =
        rewriter.unprefix(method.getDeclaringClass().getName().replace('.', '/'));
    return excludeFromEmulation.contains(unprefixedOwner + "#" + method.getName());
  }

  private Class<?> loadFromInternal(String internalName) {
    try {
      return targetLoader.loadClass(internalName.replace('/', '.'));
    } catch (ClassNotFoundException e) {
      throw (NoClassDefFoundError) new NoClassDefFoundError().initCause(e);
    }
  }

  private ClassVisitor dispatchHelper(String internalName) {
    return dispatchHelpers.computeIfAbsent(internalName, className -> {
      className += "$$Dispatch";
      ClassVisitor result = store.add(className);
      result.visit(
          Opcodes.V1_7,
          // Must be public so dispatch methods can be called from anywhere
          Opcodes.ACC_SYNTHETIC | Opcodes.ACC_PUBLIC,
          className,
          /*signature=*/ null,
          "java/lang/Object",
          new String[0]);
      return result;
    });
  }

  private static Method findInterfaceMethod(Class<?> clazz, String name, String desc) {
    return collectImplementedInterfaces(clazz, new LinkedHashSet<>())
        .stream()
        // search more subtypes before supertypes
        .sorted(DefaultMethodClassFixer.InterfaceComparator.INSTANCE)
        .map(itf -> findMethod(itf, name, desc))
        .filter(Objects::nonNull)
        .findFirst()
        .orElse((Method) null);
  }

  private static Method findMethod(Class<?> clazz, String name, String desc) {
    for (Method m : clazz.getMethods()) {
      if (m.getName().equals(name) && Type.getMethodDescriptor(m).equals(desc)) {
        return m;
      }
    }
    return null;
  }

  private static Set<Class<?>> collectImplementedInterfaces(Class<?> clazz, Set<Class<?>> dest) {
    if (clazz.isInterface()) {
      if (!dest.add(clazz)) {
        return dest;
      }
    } else if (clazz.getSuperclass() != null) {
      collectImplementedInterfaces(clazz.getSuperclass(), dest);
    }

    for (Class<?> itf : clazz.getInterfaces()) {
      collectImplementedInterfaces(itf, dest);
    }
    return dest;
  }

  /**
   * Emits instructions to load a method's parameters as arguments of a method call assumed to have
   * compatible descriptor, starting at the given local variable slot.
   */
  private static void visitLoadArgs(MethodVisitor dispatchMethod, Type neededType, int slot) {
    for (Type arg : neededType.getArgumentTypes()) {
      dispatchMethod.visitVarInsn(arg.getOpcode(Opcodes.ILOAD), slot);
      slot += arg.getSize();
    }
  }

  /** Checks whether the given class is (likely) generated by desugar itself. */
  private static boolean looksGenerated(String owner) {
    return owner.contains("$$Lambda$") || owner.endsWith("$$CC") || owner.endsWith("$$Dispatch");
  }
}
