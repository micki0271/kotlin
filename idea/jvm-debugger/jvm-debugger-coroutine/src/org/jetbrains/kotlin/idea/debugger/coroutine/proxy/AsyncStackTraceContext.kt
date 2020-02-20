/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.debugger.coroutine.proxy

import com.intellij.debugger.DebuggerContext
import com.intellij.debugger.engine.JavaValue
import com.intellij.debugger.engine.evaluation.EvaluateException
import com.intellij.debugger.engine.evaluation.EvaluationContextImpl
import com.intellij.debugger.jdi.GeneratedLocation
import com.intellij.debugger.jdi.StackFrameProxyImpl
import com.intellij.debugger.ui.impl.watch.ValueDescriptorImpl
import com.intellij.xdebugger.frame.XNamedValue
import com.sun.jdi.*
import org.jetbrains.kotlin.codegen.coroutines.CONTINUATION_VARIABLE_NAME
import org.jetbrains.kotlin.idea.debugger.evaluate.ExecutionContext
import org.jetbrains.kotlin.idea.debugger.coroutine.util.logger
import org.jetbrains.kotlin.idea.debugger.SUSPEND_LAMBDA_CLASSES
import org.jetbrains.kotlin.idea.debugger.coroutine.data.CoroutineStackFrameItem
import org.jetbrains.kotlin.idea.debugger.coroutine.data.DefaultCoroutineStackFrameItem
import org.jetbrains.kotlin.idea.debugger.coroutine.proxy.data.ContinuationHolder
import org.jetbrains.kotlin.idea.debugger.coroutine.proxy.data.ContinuationValueDescriptorImpl
import org.jetbrains.kotlin.idea.debugger.isSubtype
import org.jetbrains.kotlin.idea.debugger.safeVisibleVariableByName
import org.jetbrains.kotlin.idea.debugger.stackFrame.KotlinStackFrame

class AsyncStackTraceContext(
    val context: ExecutionContext,
    val method: Method,
    private val initialContinuation: ContinuationHolder
) {
    val log by logger

    private companion object {
        const val DEBUG_METADATA_KT = "kotlin.coroutines.jvm.internal.DebugMetadataKt"
    }

    fun getAsyncStackTraceIfAny(): List<CoroutineStackFrameItem> {
        val frames = mutableListOf<CoroutineStackFrameItem>()
        try {
            collectFramesRecursively(initialContinuation, frames)
        } catch (e: Exception) {
            log.error("Error while looking for variables.", e)
        }
        return frames
    }

    private fun collectFramesRecursively(continuation: ContinuationHolder, consumer: MutableList<CoroutineStackFrameItem>) {
        val debugMetadataKtType = debugMetadataKtType() ?: return
        val location = createLocation(continuation, debugMetadataKtType)

        location?.let {
            val spilledVariables = getSpilledVariables(continuation, debugMetadataKtType) ?: emptyList()
            consumer.add(DefaultCoroutineStackFrameItem(location, spilledVariables))
        }

        continuation.findCompletion()?.let {
            collectFramesRecursively(it, consumer)
        }
    }

    private fun createLocation(continuation: ContinuationHolder, debugMetadataKtType: ClassType): GeneratedLocation? {
        val instance = invokeGetStackTraceElement(continuation, debugMetadataKtType) ?: return null
        val className = context.invokeMethodAsString(instance, "getClassName") ?: return null
        val methodName = context.invokeMethodAsString(instance, "getMethodName") ?: return null
        val lineNumber = context.invokeMethodAsInt(instance, "getLineNumber")?.takeIf {
            it >= 0
        } ?: return null
        val locationClass = context.findClassSafe(className) ?: return null
        return GeneratedLocation(context.debugProcess, locationClass, methodName, lineNumber)
    }

    private fun invokeGetStackTraceElement(continuation: ContinuationHolder, debugMetadataKtType: ClassType): ObjectReference? {
        val stackTraceElement =
            context.invokeMethodAsObject(debugMetadataKtType, "getStackTraceElement", continuation.value()) ?: return null

        stackTraceElement.referenceType().takeIf { it.name() == StackTraceElement::class.java.name } ?: return null

        context.keepReference(stackTraceElement)
        return stackTraceElement
    }

    fun getSpilledVariables(): List<XNamedValue>? {
        debugMetadataKtType()?.let {
            return getSpilledVariables(initialContinuation, it)
        }
        return null
    }

    fun getSpilledVariables(continuation: ContinuationHolder, debugMetadataKtType: ClassType): List<XNamedValue>? {
        val rawSpilledVariables =
            context.invokeMethodAsArray(
                debugMetadataKtType,
                "getSpilledVariableFieldMapping",
                "(Lkotlin/coroutines/jvm/internal/BaseContinuationImpl;)[Ljava/lang/String;",
                continuation.value()
            ) ?: return null

        context.keepReference(rawSpilledVariables)

        val length = rawSpilledVariables.length() / 2
        val spilledVariables = ArrayList<XNamedValue>(length)

        for (index in 0 until length) {
            val (fieldName, variableName) = getFieldVariableName(rawSpilledVariables, index) ?: continue;

            val valueDescriptor = ContinuationValueDescriptorImpl(context.project, continuation, fieldName, variableName);

            spilledVariables += JavaValue.create(
                null,
                valueDescriptor,
                context.evaluationContext,
                context.debugProcess.xdebugProcess!!.nodeManager,
                false
            )
        }

        return spilledVariables
    }

    private fun getFieldVariableName(rawSpilledVariables: ArrayReference, index: Int): FieldVariable? {
        val fieldName = (rawSpilledVariables.getValue(2 * index) as? StringReference)?.value() ?: return null
        val variableName = (rawSpilledVariables.getValue(2 * index + 1) as? StringReference)?.value() ?: return null
        return FieldVariable(fieldName, variableName)
    }

    data class FieldVariable(val fieldName: String, val variableName: String)

    fun buildXStackFrame(frame: StackFrameProxyImpl): KotlinStackFrame {
        return KotlinStackFrame(frame)
    }

    private fun debugMetadataKtType(): ClassType? {
        val debugMetadataKtType = context.findClassSafe(DEBUG_METADATA_KT)
        if (debugMetadataKtType != null) {
            return debugMetadataKtType
        } else {
            log.warn("Continuation information found but no $DEBUG_METADATA_KT class exists. Please check kotlin-stdlib version.")
        }
        return null
    }
}

