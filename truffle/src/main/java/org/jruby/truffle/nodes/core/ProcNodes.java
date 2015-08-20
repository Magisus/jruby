/*
 * Copyright (c) 2013, 2015 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.jruby.truffle.nodes.core;

import org.jruby.ast.ArgsNode;
import org.jruby.runtime.ArgumentDescriptor;
import org.jruby.runtime.Helpers;
import org.jruby.truffle.nodes.RubyGuards;
import org.jruby.truffle.nodes.RubyNode;
import org.jruby.truffle.nodes.core.array.ArrayNodes;
import org.jruby.truffle.nodes.yield.YieldDispatchHeadNode;
import org.jruby.truffle.runtime.NotProvided;
import org.jruby.truffle.runtime.RubyArguments;
import org.jruby.truffle.runtime.RubyCallStack;
import org.jruby.truffle.runtime.RubyContext;
import org.jruby.truffle.runtime.control.RaiseException;
import org.jruby.truffle.runtime.layouts.Layouts;
import org.jruby.truffle.runtime.methods.InternalMethod;
import org.jruby.truffle.runtime.methods.SharedMethodInfo;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.NodeChildren;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.frame.FrameInstance.FrameAccess;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.object.DynamicObjectFactory;
import com.oracle.truffle.api.source.NullSourceSection;
import com.oracle.truffle.api.source.SourceSection;

@CoreClass(name = "Proc")
public abstract class ProcNodes {

    public static Object rootCall(DynamicObject proc, Object... args) {
        assert RubyGuards.isRubyProc(proc);

        return Layouts.PROC.getCallTargetForType(proc).call(RubyArguments.pack(
                Layouts.PROC.getMethod(proc),
                Layouts.PROC.getDeclarationFrame(proc),
                Layouts.PROC.getSelf(proc),
                Layouts.PROC.getBlock(proc),
                args));
    }

    public static DynamicObject createRubyProc(DynamicObject procClass, Type type, SharedMethodInfo sharedMethodInfo, CallTarget callTargetForProcs,
                                               CallTarget callTargetForLambdas, MaterializedFrame declarationFrame, InternalMethod method,
                                               Object self, DynamicObject block) {
        return createRubyProc(Layouts.CLASS.getInstanceFactory(procClass),
                type, sharedMethodInfo, callTargetForProcs,
                callTargetForLambdas, declarationFrame, method,
                self, block);
    }

    public static DynamicObject createRubyProc(DynamicObjectFactory instanceFactory, Type type, SharedMethodInfo sharedMethodInfo, CallTarget callTargetForProcs,
                                          CallTarget callTargetForLambdas, MaterializedFrame declarationFrame, InternalMethod method,
                                          Object self, DynamicObject block) {
        assert block == null || RubyGuards.isRubyProc(block);
        final CallTarget callTargetForType = (type == Type.PROC) ? callTargetForProcs : callTargetForLambdas;
        return Layouts.PROC.createProc(instanceFactory, type, sharedMethodInfo, callTargetForType, callTargetForLambdas, declarationFrame, method, self, block);
    }

    public enum Type {
        PROC, LAMBDA
    }

    @CoreMethod(names = "new", constructor = true, needsBlock = true)
    @NodeChildren({
            @NodeChild(type = RubyNode.class, value = "procClass"),
            @NodeChild(type = RubyNode.class, value = "block")
    })
    public abstract static class ProcNewNode extends CoreMethodNode {

        public ProcNewNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        public abstract DynamicObject executeProcNew(DynamicObject procClass, Object block);

        @TruffleBoundary
        @Specialization
        public DynamicObject proc(DynamicObject procClass, NotProvided block) {
            final Frame parentFrame = RubyCallStack.getCallerFrame(getContext()).getFrame(FrameAccess.READ_ONLY, true);
            final DynamicObject parentBlock = RubyArguments.getBlock(parentFrame.getArguments());

            if (parentBlock == null) {
                CompilerDirectives.transferToInterpreter();
                throw new RaiseException(getContext().getCoreLibrary().argumentError("tried to create Proc object without a block", this));
            }

            return executeProcNew(procClass, parentBlock);
        }

        @Specialization(guards = "procClass == metaClass(block)")
        public DynamicObject procNormal(DynamicObject procClass, DynamicObject block) {
            return block;
        }

        @Specialization(guards = "procClass != metaClass(block)")
        public DynamicObject procSpecial(DynamicObject procClass, DynamicObject block) {
            // Instantiate a new instance of procClass as classes do not correspond
            return ProcNodes.createRubyProc(
                    procClass,
                    Layouts.PROC.getType(block),
                    Layouts.PROC.getSharedMethodInfo(block),
                    Layouts.PROC.getCallTargetForType(block),
                    Layouts.PROC.getCallTargetForLambdas(block),
                    Layouts.PROC.getDeclarationFrame(block),
                    Layouts.PROC.getMethod(block),
                    Layouts.PROC.getSelf(block),
                    Layouts.PROC.getBlock(block));
        }

        protected DynamicObject metaClass(DynamicObject object) {
            return Layouts.BASIC_OBJECT.getMetaClass(object);
        }

    }

    @CoreMethod(names = "arity")
    public abstract static class ArityNode extends CoreMethodArrayArgumentsNode {

        public ArityNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @Specialization
        public int arity(DynamicObject proc) {
            return Layouts.PROC.getSharedMethodInfo(proc).getArity().getArityNumber();
        }

    }

    @CoreMethod(names = "binding")
    public abstract static class BindingNode extends CoreMethodArrayArgumentsNode {

        public BindingNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @Specialization
        public Object binding(DynamicObject proc) {
            final MaterializedFrame frame = Layouts.PROC.getDeclarationFrame(proc);

            return BindingNodes.createRubyBinding(getContext().getCoreLibrary().getBindingClass(),
                    RubyArguments.getSelf(frame.getArguments()),
                    frame);
        }

    }

    @CoreMethod(names = {"call", "[]", "yield"}, rest = true, needsBlock = true)
    public abstract static class CallNode extends CoreMethodArrayArgumentsNode {

        @Child private YieldDispatchHeadNode yieldNode;

        public CallNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
            yieldNode = new YieldDispatchHeadNode(context);
        }

        @Specialization
        public Object call(VirtualFrame frame, DynamicObject proc, Object[] args, NotProvided block) {
            return yieldNode.dispatch(frame, proc, args);
        }

        @Specialization(guards = "isRubyProc(block)")
        public Object call(VirtualFrame frame, DynamicObject proc, Object[] args, DynamicObject block) {
            return yieldNode.dispatchWithModifiedBlock(frame, proc, block, args);
        }

    }

    @CoreMethod(names = "lambda?")
    public abstract static class LambdaNode extends CoreMethodArrayArgumentsNode {

        public LambdaNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @Specialization
        public boolean lambda(DynamicObject proc) {
            return Layouts.PROC.getType(proc) == Type.LAMBDA;
        }

    }

    @CoreMethod(names = "parameters")
    public abstract static class ParametersNode extends CoreMethodArrayArgumentsNode {

        public ParametersNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @TruffleBoundary
        @Specialization
        public DynamicObject parameters(DynamicObject proc) {
            final ArgsNode argsNode = Layouts.PROC.getSharedMethodInfo(proc).getParseTree().findFirstChild(ArgsNode.class);

            final ArgumentDescriptor[] argsDesc = Helpers.argsNodeToArgumentDescriptors(argsNode);

            return getContext().toTruffle(Helpers.argumentDescriptorsToParameters(getContext().getRuntime(),
                    argsDesc, Layouts.PROC.getType(proc) == Type.LAMBDA));
        }

    }

    @CoreMethod(names = "source_location")
    public abstract static class SourceLocationNode extends CoreMethodArrayArgumentsNode {

        public SourceLocationNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @TruffleBoundary
        @Specialization
        public Object sourceLocation(DynamicObject proc) {
            SourceSection sourceSection = Layouts.PROC.getSharedMethodInfo(proc).getSourceSection();

            if (sourceSection instanceof NullSourceSection) {
                return nil();
            } else {
                DynamicObject file = createString(sourceSection.getSource().getName());
                return ArrayNodes.fromObjects(getContext().getCoreLibrary().getArrayClass(),
                        file, sourceSection.getStartLine());
            }
        }

    }

}
