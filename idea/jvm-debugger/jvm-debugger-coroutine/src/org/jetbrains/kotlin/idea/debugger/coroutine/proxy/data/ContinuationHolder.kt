/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.debugger.coroutine.proxy.data

import com.intellij.debugger.jdi.StackFrameProxyImpl
import com.sun.jdi.*
import org.jetbrains.kotlin.codegen.coroutines.CONTINUATION_VARIABLE_NAME
import org.jetbrains.kotlin.idea.debugger.SUSPEND_LAMBDA_CLASSES
import org.jetbrains.kotlin.idea.debugger.coroutine.proxy.AsyncStackTraceContext
import org.jetbrains.kotlin.idea.debugger.evaluate.ExecutionContext
import org.jetbrains.kotlin.idea.debugger.isSubtype
import org.jetbrains.kotlin.idea.debugger.safeVisibleVariableByName

data class ContinuationHolder(val continuation: ObjectReference) {
    fun context(context: ExecutionContext, method: Method) =
        AsyncStackTraceContext(context, method, this)

    fun referenceType(): ClassType? =
        continuation.referenceType() as? ClassType

    fun value() =
        continuation

    fun field(field: Field): Value? =
        continuation.getValue(field)

    fun findCompletion(): ContinuationHolder? {// continuation.type().isSubtype(BASE_CONTINUATION_IMPL_CLASS_NAME) (continuation.type() as ClassType).fieldByName(COMPLETION_FIELD_NAME)
        val type = continuation.type()
        if (type is ClassType && type.isSubtype(BASE_CONTINUATION_IMPL_CLASS_NAME)) {
            val completionField = type.fieldByName(COMPLETION_FIELD_NAME) ?: return null
            return ContinuationHolder(continuation.getValue(completionField) as? ObjectReference ?: return null)
        }
        return null
    }

    companion object {
        val BASE_CONTINUATION_IMPL_CLASS_NAME = "kotlin.coroutines.jvm.internal.BaseContinuationImpl"
        val COMPLETION_FIELD_NAME = "completion"

        fun lookup(context: ExecutionContext, method: Method): ContinuationHolder? {
            if (isInvokeSuspendMethod(method)) {
                val tmp = context.frameProxy.thisObject() ?: return null
                if (!isSuspendLambda(tmp.referenceType()))
                    return null
                return ContinuationHolder(tmp)
            } else if (isSuspendMethod(method)) {
                var continuation = getVariableValue(context.frameProxy, CONTINUATION_VARIABLE_NAME) ?: return null
                context.keepReference(continuation)
                return ContinuationHolder(continuation)
            } else {
                return null
            }
        }

        private fun getVariableValue(sfp: StackFrameProxyImpl, variableName: String) : ObjectReference? {
            val continuationVariable = sfp.safeVisibleVariableByName(variableName) ?: return null
            return sfp.getValue(continuationVariable) as? ObjectReference ?: return null
        }

        private fun isInvokeSuspendMethod(method: Method): Boolean =
            method.name() == "invokeSuspend" && method.signature() == "(Ljava/lang/Object;)Ljava/lang/Object;"

        /**
         * @TODO
         * Checks if the method has a Continuation as input parameter.
         */
        private fun isSuspendMethod(method: Method): Boolean {
            var argTypes = method.argumentTypes()
            for (arg in argTypes) {
                println(arg)
            }
            return "Lkotlin/coroutines/Continuation;)" in method.signature()
        }

        private fun isSuspendLambda(referenceType: ReferenceType): Boolean =
            SUSPEND_LAMBDA_CLASSES.any { referenceType.isSubtype(it) }

        /**
         * Find continuation for the [frame]
         * Gets current CoroutineInfo.lastObservedFrame and finds next frames in it until null or needed stackTraceElement is found
         * @return null if matching continuation is not found or is not BaseContinuationImpl
         */
        fun lookup(context: ExecutionContext, initialContinuation: ObjectReference?, frame: StackTraceElement): ContinuationHolder? {
            var continuation = initialContinuation ?: return null
            val classLine = ClassNameLineNumber(frame.className, frame.lineNumber)

            do {
                val position = getClassAndLineNumber(context, continuation)
                // while continuation is BaseContinuationImpl and it's frame equals to the current
                continuation = getNextFrame(context, continuation) ?: return null
            } while (isSubtypeOfBaseContinuationImpl(continuation.type()) && position != classLine)


            return if (isSubtypeOfBaseContinuationImpl(continuation.type()))
                ContinuationHolder(continuation)
            else
                return null
        }

        /**
         * Finds previous Continuation for this Continuation (completion field in BaseContinuationImpl)
         * @return null if given ObjectReference is not a BaseContinuationImpl instance or completion is null
         */
        private fun getNextFrame(context: ExecutionContext, continuation: ObjectReference ): ObjectReference? {
            if (!isSubtypeOfBaseContinuationImpl(continuation.type()))
                return null
            val type = continuation.type() as ClassType
            val next = type.concreteMethodByName("getCompletion", "()Lkotlin/coroutines/Continuation;")
            return context.invokeMethod(continuation, next, emptyList()) as? ObjectReference
        }

        fun isSubtypeOfBaseContinuationImpl(type: Type) =
            type.isSubtype(BASE_CONTINUATION_IMPL_CLASS_NAME)

        data class ClassNameLineNumber(val className: String?, val lineNumber: Int?)

        private fun getClassAndLineNumber(context: ExecutionContext, continuation: ObjectReference): ClassNameLineNumber {
            val objectReference = findStackTraceElement(context, continuation) ?: return ClassNameLineNumber(null, null)
            val classStackTraceElement = context.findClass("java.lang.StackTraceElement") as ClassType
            val getClassName = classStackTraceElement.concreteMethodByName("getClassName", "()Ljava/lang/String;")
            val getLineNumber = classStackTraceElement.concreteMethodByName("getLineNumber", "()I")
            val className = (context.invokeMethod(objectReference, getClassName, emptyList()) as StringReference).value()
            val lineNumber = (context.invokeMethod(objectReference, getLineNumber, emptyList()) as IntegerValue).value()
            return ClassNameLineNumber(className, lineNumber)
        }

        private fun findStackTraceElement(context: ExecutionContext, continuation: ObjectReference): ObjectReference? {
            val classType = continuation.type() as ClassType
            val methodGetStackTraceElement = classType.concreteMethodByName("getStackTraceElement", "()Ljava/lang/StackTraceElement;")
            return context.invokeMethod(continuation, methodGetStackTraceElement, emptyList()) as? ObjectReference
        }

    }
}

