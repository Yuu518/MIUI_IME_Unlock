package com.xposed.miuiime

import android.annotation.TargetApi
import android.content.Context
import android.os.Binder
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import com.github.kyuubiran.ezxhelper.init.EzXHelperInit
import com.github.kyuubiran.ezxhelper.utils.Log
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.getObjectAs
import com.github.kyuubiran.ezxhelper.utils.getStaticObject
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import com.github.kyuubiran.ezxhelper.utils.hookReplace
import com.github.kyuubiran.ezxhelper.utils.hookReturnConstant
import com.github.kyuubiran.ezxhelper.utils.invokeMethodAuto
import com.github.kyuubiran.ezxhelper.utils.invokeMethodAutoAs
import com.github.kyuubiran.ezxhelper.utils.invokeStaticMethodAuto
import com.github.kyuubiran.ezxhelper.utils.loadClassOrNull
import com.github.kyuubiran.ezxhelper.utils.putStaticObject
import com.github.kyuubiran.ezxhelper.utils.sameAs
import dalvik.system.BaseDexClassLoader
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Method

private const val TAG = "miuiime"
private const val EDGE_TO_EDGE_TARGET_SDK = 35
private const val LEGACY_BOTTOM_MARGIN_TARGET_SDK = EDGE_TO_EDGE_TARGET_SDK - 1
private const val BOTTOM_AREA_INSET_RETRY_DELAY_MILLIS = 200L

internal fun shouldApplyBottomAreaInsetFix(deviceSdkVersion: Int, imeTargetSdkVersion: Int): Boolean =
    deviceSdkVersion >= EDGE_TO_EDGE_TARGET_SDK &&
        imeTargetSdkVersion >= EDGE_TO_EDGE_TARGET_SDK

internal fun resolveBottomMarginTargetSdk(deviceSdkVersion: Int, imeTargetSdkVersion: Int): Int =
    if (shouldApplyBottomAreaInsetFix(deviceSdkVersion, imeTargetSdkVersion)) {
        LEGACY_BOTTOM_MARGIN_TARGET_SDK
    } else {
        imeTargetSdkVersion
    }

internal fun resolveBottomAreaTranslationY(
    isNavigationHandleShown: Boolean,
    navigationInsetBottom: Int?,
    bottomAreaOverflow: Int?,
): Float? {
    if (!isNavigationHandleShown) return 0f
    val navigationInset = navigationInsetBottom?.coerceAtLeast(0) ?: return null
    val availableOverflow = bottomAreaOverflow?.coerceAtLeast(0) ?: return null
    val safeTranslation = minOf(navigationInset, availableOverflow)
    return -safeTranslation.toFloat()
}

class MainHook : IXposedHookLoadPackage {
    private val miuiImeList: List<String> = listOf(
        "com.iflytek.inputmethod.miui",
        "com.sohu.inputmethod.sogou.xiaomi",
        "com.baidu.input_mi",
        "com.miui.catcherpatch",
        "com.xiaomi.type",
    )
    private val hookedBottomManagerClasses = mutableSetOf<Class<*>>()
    private var navBarColor: Int? = null

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 检查是否支持全面屏优化
        if (PropertyUtils["ro.miui.support_miui_ime_bottom", "0"] != "1") return
        EzXHelperInit.initHandleLoadPackage(lpparam)
        EzXHelperInit.setLogTag(TAG)
        Log.i("miuiime is supported")

        when (lpparam.packageName) {
            "android" -> startPermissionHook()
            "com.miui.phrase" -> startPackageValidationHook(lpparam)
            else -> startHook(lpparam)
        }
    }

    private fun startHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 检查是否为小米定制输入法
        val isNonCustomize = !miuiImeList.contains(lpparam.packageName)
        if (isNonCustomize) {
            val sInputMethodServiceInjector =
                loadClassOrNull("android.inputmethodservice.InputMethodServiceInjector")
                    ?: loadClassOrNull("android.inputmethodservice.InputMethodServiceStubImpl")

            sInputMethodServiceInjector?.also {
                hookSIsImeSupport(it)
                hookIsXiaoAiEnable(it)
                setPhraseBgColor(it)
            } ?: Log.e("Failed:Class not found: InputMethodServiceInjector")
        }

        hookDeleteNotSupportIme(
            "android.inputmethodservice.InputMethodServiceInjector\$MiuiSwitchInputMethodListener",
            lpparam.classLoader
        )

        // 获取常用语的ClassLoader
        findMethod("android.inputmethodservice.InputMethodModuleManager") {
            name == "loadDex" && parameterTypes.sameAs(ClassLoader::class.java, String::class.java)
        }.hookBefore { param ->
            val loader = param.args[0] as ClassLoader
            val dexPath = param.args[1] as String
            // 已加载时复用现有类，否则先添加动态模块的 dex 路径。
            if (loader !is BaseDexClassLoader) throw NoSuchMethodException("addDexPath method not found.")
            val bottomManagerClass = runCatching {
                Class.forName("com.miui.inputmethod.InputMethodBottomManager", true, loader)
            }.getOrNull() ?: run {
                loader.invokeMethodAuto("addDexPath", dexPath)
                loadClassOrNull("com.miui.inputmethod.InputMethodBottomManager", loader)
            }

            if (bottomManagerClass == null) {
                Log.e("Failed:Class not found: com.miui.inputmethod.InputMethodBottomManager")
            } else if (markBottomManagerClassHooked(bottomManagerClass)) {
                hookDeleteNotSupportIme(
                    "com.miui.inputmethod.InputMethodBottomManager\$MiuiSwitchInputMethodListener",
                    loader
                )

                val clazz = bottomManagerClass
                if (isNonCustomize) {
                    hookSIsImeSupport(clazz)
                    hookIsXiaoAiEnable(clazz)
                    if (shouldApplyBottomAreaInsetFix(
                            Build.VERSION.SDK_INT,
                            lpparam.appInfo.targetSdkVersion,
                        )
                    ) {
                        hookLegacyBottomMargin(clazz)
                        hookBottomAreaNavigationInset(clazz)
                    }
                }

                // 针对A11的修复切换输入法列表
                clazz.getDeclaredMethod("getSupportIme").hookReplace { _ ->
                    clazz.getStaticObject("sBottomViewHelper")
                        .getObjectAs<InputMethodManager>("mImm").enabledInputMethodList
                }
            }
            param.result = null
        }

        Log.i("Hook MIUI IME Done!")
    }

    private fun markBottomManagerClassHooked(clazz: Class<*>): Boolean =
        synchronized(hookedBottomManagerClasses) {
            hookedBottomManagerClasses.add(clazz)
        }

    /**
     * 跳过包名检查，直接开启输入法优化
     *
     * @param clazz 声明或继承字段的类
     */
    private fun hookSIsImeSupport(clazz: Class<*>) {
        kotlin.runCatching {
            clazz.putStaticObject("sIsImeSupport", 1)
            Log.i("Success:Hook field sIsImeSupport")
        }.onFailure {
            Log.i("Failed:Hook field sIsImeSupport")
            Log.i(it)
        }
    }

    /**
     * 小爱语音输入按钮失效修复
     *
     * @param clazz 声明或继承方法的类
     */
    private fun hookIsXiaoAiEnable(clazz: Class<*>) {
        kotlin.runCatching {
            clazz.getMethod("isXiaoAiEnable").hookReturnConstant(false)
        }.onFailure {
            Log.i("Failed:Hook method isXiaoAiEnable")
            Log.i(it)
        }
    }

    /**
     * HyperOS 4 no longer removes the gesture-area remainder for target SDK 35+ IMEs.
     * Non-customized IMEs enabled by this module still use the legacy layout, so keep
     * this single vendor layout check on its pre-edge-to-edge path.
     *
     * @param clazz com.miui.inputmethod.InputMethodBottomManager
     */
    private fun hookLegacyBottomMargin(clazz: Class<*>) {
        kotlin.runCatching {
            clazz.getDeclaredMethod("getTargetSdkVersion", Context::class.java).hookAfter { param ->
                val targetSdkVersion = param.result as? Int ?: return@hookAfter
                val resolvedTargetSdkVersion = resolveBottomMarginTargetSdk(
                    Build.VERSION.SDK_INT,
                    targetSdkVersion
                )
                if (resolvedTargetSdkVersion != targetSdkVersion) {
                    param.result = resolvedTargetSdkVersion
                }
            }
            Log.i("Success:Hook HyperOS 4 bottom margin")
        }.onFailure {
            Log.i("Failed:Hook HyperOS 4 bottom margin")
            Log.i(it)
        }
    }

    /**
     * Target SDK 35+ IMEs already reserve the gesture navigation inset. Move the
     * MIUI bottom area over that reserved space, capped by the vendor-computed
     * applied bottom margin so its background still reaches the bottom of the window.
     *
     * @param clazz com.miui.inputmethod.InputMethodBottomManager
     */
    private fun hookBottomAreaNavigationInset(clazz: Class<*>) {
        kotlin.runCatching {
            val inputMethodUtilClass = Class.forName(
                "com.miui.inputmethod.InputMethodUtil",
                false,
                clazz.classLoader,
            )
            val isShowNavigationHandleMethod = inputMethodUtilClass
                .getDeclaredMethod("isShowNavigationHandle").apply {
                isAccessible = true
            }

            clazz.getDeclaredMethod("setMiuiBottomMargin", Context::class.java).hookAfter {
                kotlin.runCatching {
                    val bottomArea = clazz.getStaticObject("sBottomViewHelper")
                        .getObjectAs<View>("mMiuiBottomArea")
                    applyBottomAreaNavigationInset(
                        bottomArea,
                        isShowNavigationHandleMethod,
                    )
                    bottomArea.post {
                        applyBottomAreaNavigationInset(
                            bottomArea,
                            isShowNavigationHandleMethod,
                        )
                    }
                    // Some IMEs can run the first posted callback before their window
                    // receives navigation-bar insets. Retry once after initial layout.
                    bottomArea.postDelayed({
                        applyBottomAreaNavigationInset(
                            bottomArea,
                            isShowNavigationHandleMethod,
                        )
                    }, BOTTOM_AREA_INSET_RETRY_DELAY_MILLIS)
                }.onFailure {
                    Log.i("Failed:Apply HyperOS 4 bottom area inset")
                    Log.i(it)
                }
            }
            Log.i("Success:Hook HyperOS 4 bottom area inset")
        }.onFailure {
            Log.i("Failed:Hook HyperOS 4 bottom area inset")
            Log.i(it)
        }
    }

    @TargetApi(Build.VERSION_CODES.R)
    private fun applyBottomAreaNavigationInset(
        bottomArea: View,
        isShowNavigationHandleMethod: Method,
    ) {
        kotlin.runCatching {
            val isNavigationHandleShown = isShowNavigationHandleMethod.invoke(null) as Boolean
            val navigationInsetBottom = bottomArea.rootWindowInsets
                ?.getInsets(WindowInsets.Type.navigationBars())
                ?.bottom
            val bottomAreaOverflow = (bottomArea.layoutParams as? ViewGroup.MarginLayoutParams)
                ?.bottomMargin
                ?.let { bottomMargin ->
                    (-bottomMargin.toLong()).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
                }
            val translationY = resolveBottomAreaTranslationY(
                isNavigationHandleShown,
                navigationInsetBottom,
                bottomAreaOverflow,
            ) ?: return@runCatching
            bottomArea.translationY = translationY
        }.onFailure {
            Log.i("Failed:Apply HyperOS 4 bottom area inset")
            Log.i(it)
        }
    }

    /**
     * 在适当的时机修改抬高区域背景颜色
     *
     * @param clazz 声明或继承字段的类
     */
    private fun setPhraseBgColor(clazz: Class<*>) {
        kotlin.runCatching {
            // 导航栏颜色被设置后, 将颜色存储起来并传递给常用语
            findMethod("com.android.internal.policy.PhoneWindow") {
                name == "setNavigationBarColor" && parameterTypes.sameAs(Int::class.java)
            }.hookAfter { param ->
                if (param.args[0] == 0) return@hookAfter

                navBarColor = param.args[0] as Int
                customizeBottomViewColor(clazz)
            }

            // 当常用语被创建后, 将背景颜色设置为存储的导航栏颜色
            clazz.findMethod { name == "addMiuiBottomView" }.hookAfter {
                customizeBottomViewColor(clazz)
            }
        }.onFailure {
            Log.i("Failed to set the color of the MiuiBottomView")
            Log.i(it)
        }
    }

    /**
     * 将导航栏颜色赋值给输入法优化的底图
     *
     * @param clazz 声明或继承字段的类
     */
    private fun customizeBottomViewColor(clazz: Class<*>) {
        navBarColor?.let {
            val color = -0x1 - it
            clazz.invokeStaticMethodAuto(
                "customizeBottomViewColor",
                true, navBarColor, color or -0x1000000, color or 0x66000000
            )
        }
    }

    /**
     * 针对A10的修复切换输入法列表
     *
     * @param className 声明或继承方法的类的名称
     */
    private fun hookDeleteNotSupportIme(className: String, classLoader: ClassLoader) {
        kotlin.runCatching {
            findMethod(className, classLoader) { name == "deleteNotSupportIme" }
                .hookReturnConstant(null)
        }.onFailure {
            Log.i("Failed:Hook method deleteNotSupportIme")
            Log.i(it)
        }
    }

    /**
     * Hook 获取应用列表权限，使当前输入法可见其他输入法。
     * 用于修复部分输入法（搜狗输入法小米版等）缺少获取输入法列表权限，导致切换输入法功能不能显示其他输入法的问题。
     */
    private fun startPermissionHook() {
        runCatching {
            findMethod("com.android.server.inputmethod.InputMethodManagerServiceImpl") {
                name == "isCallingBetweenCustomIME"
            }.hookAfter { param ->
                if (param.result == true) return@hookAfter
                val context = param.args[0] as Context
                val uid = param.args[1] as Int
                val currentInputMethodPackageName = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.DEFAULT_INPUT_METHOD
                )?.substringBefore('/') ?: return@hookAfter
                val packagesForUid = context.packageManager.getPackagesForUid(uid) ?: return@hookAfter
                if (packagesForUid.contains(currentInputMethodPackageName)) {
                    param.result = true
                }
            }
        }.onFailure {
            Log.i("Failed: Hook method isCallingBetweenCustomIME")
            Log.i(it)
        }
    }

    /**
     * Hook InputProvider的输入法白名单，修复当前输入法无法获得剪贴板的问题
     */
    private fun startPackageValidationHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            System.loadLibrary("dexkit")
            DexKitBridge.create(lpparam.appInfo.sourceDir).use { bridge ->
                bridge.findMethod {
                    matcher {
                        declaredClass = "com.miui.provider.InputProvider"
                        returnType = "boolean"
                        usingStrings(
                            "InputProvider",
                            "Invalid caller UID: ",
                            "No package name for UID: ",
                            "Package validation failed: ",
                            "Unexpected error during package validation"
                        )
                    }
                }.singleOrNull()?.getMethodInstance(lpparam.classLoader)?.hookBefore { param ->
                    val callingUid = Binder.getCallingUid()
                    val context = param.thisObject.invokeMethodAutoAs<Context>("getContext") ?: return@hookBefore
                    val packagesForUid = context.packageManager.getPackagesForUid(callingUid) ?: return@hookBefore
                    val currentInputMethodPackageName = Settings.Secure.getString(
                        context.contentResolver,
                        Settings.Secure.DEFAULT_INPUT_METHOD
                    )?.substringBefore('/') ?: return@hookBefore
                    if (packagesForUid.contains(currentInputMethodPackageName)) {
                        param.result = true
                    }
                }
            }
        }.onFailure {
            Log.i("Failed: Hook package validation")
            Log.i(it)
        }
    }
}
