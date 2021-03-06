// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.diagnostic

import com.intellij.openapi.application.ApplicationManager
import kotlin.reflect.KProperty
import kotlin.reflect.jvm.javaGetter

inline fun <reified T : Any> logger() = Logger.getInstance(T::class.java)

fun logger(category: String) = Logger.getInstance(category)

/**
 * Get logger instance to be used in Kotlin package methods. Usage:
 * ```
 * private val LOG: Logger = logger(::LOG) // define at top level of the file containing the function
 * ```

 * Notice explicit type declaration which can't be skipped in this case.
 */
fun logger(field: KProperty<Logger>) = Logger.getInstance(field.javaGetter!!.declaringClass)

inline fun Logger.debug(e: Exception? = null, lazyMessage: () -> String) {
  if (isDebugEnabled) {
    debug(lazyMessage(), e)
  }
}

inline fun Logger.debugOrInfoIfTestMode(e: Exception? = null, lazyMessage: () -> String) {
  if (ApplicationManager.getApplication()?.isUnitTestMode == true) {
    info(lazyMessage())
  }
  else {
    debug(e, lazyMessage)
  }
}

inline fun <T> Logger.runAndLogException(runnable: () -> T): T? {
  try {
    return runnable()
  }
  catch (e: Throwable) {
    errorIfNotControlFlow(e)
    return null
  }
}

fun Logger.errorIfNotControlFlow(e: Throwable) {
  if (e is ControlFlowException) {
    throw e
  }
  else {
    error(e)
  }
}

inline fun Logger.errorIfNotControlFlow(e: Throwable, lazyMessage: () -> String) {
  if (e is ControlFlowException) {
    throw e
  }
  else {
    error(lazyMessage())
  }
}