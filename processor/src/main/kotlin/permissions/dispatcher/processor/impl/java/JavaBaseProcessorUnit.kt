package permissions.dispatcher.processor.impl.java

import androidx.annotation.NonNull
import com.squareup.javapoet.AnnotationSpec
import com.squareup.javapoet.ArrayTypeName
import com.squareup.javapoet.ClassName
import com.squareup.javapoet.CodeBlock
import com.squareup.javapoet.FieldSpec
import com.squareup.javapoet.JavaFile
import com.squareup.javapoet.MethodSpec
import com.squareup.javapoet.ParameterSpec
import com.squareup.javapoet.ParameterizedTypeName
import com.squareup.javapoet.TypeName
import com.squareup.javapoet.TypeSpec
import permissions.dispatcher.NeedsPermission
import permissions.dispatcher.processor.JavaProcessorUnit
import permissions.dispatcher.processor.RequestCodeProvider
import permissions.dispatcher.processor.RuntimePermissionsElement
import permissions.dispatcher.processor.util.FILE_COMMENT
import permissions.dispatcher.processor.util.pendingRequestFieldName
import permissions.dispatcher.processor.util.permissionFieldName
import permissions.dispatcher.processor.util.permissionRequestTypeName
import permissions.dispatcher.processor.util.permissionValue
import permissions.dispatcher.processor.util.requestCodeFieldName
import permissions.dispatcher.processor.util.simpleString
import permissions.dispatcher.processor.util.typeNameOf
import permissions.dispatcher.processor.util.varargsParametersCodeBlock
import permissions.dispatcher.processor.util.withPermissionCheckMethodName
import java.util.ArrayList
import javax.annotation.processing.Messager
import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.Modifier

/**
 * Base class for [JavaProcessorUnit] implementations.
 * <p>
 * This generates the parts of code independent from specific permission method signatures for different target objects.
 */
abstract class JavaBaseProcessorUnit(val messager: Messager) : JavaProcessorUnit {

    protected val PERMISSION_UTILS: ClassName = ClassName.get("permissions.dispatcher", "PermissionUtils")
    private val BUILD = ClassName.get("android.os", "Build")
    private val MANIFEST_WRITE_SETTING = "android.permission.WRITE_SETTINGS"
    private val MANIFEST_SYSTEM_ALERT_WINDOW = "android.permission.SYSTEM_ALERT_WINDOW"
    private val ADD_WITH_CHECK_BODY_MAP = hashMapOf(MANIFEST_SYSTEM_ALERT_WINDOW to SystemAlertWindowHelper(), MANIFEST_WRITE_SETTING to WriteSettingsHelper())

    /**
     * Creates the JavaFile for the provided @RuntimePermissions element.
     * <p>
     * This will delegate to other methods that compose generated code.
     */
    override final fun createFile(rpe: RuntimePermissionsElement, requestCodeProvider: RequestCodeProvider): JavaFile {
        return JavaFile.builder(rpe.packageName, createTypeSpec(rpe, requestCodeProvider))
                .addFileComment(FILE_COMMENT)
                .build()
    }

    /* Begin abstract */

    abstract fun addRequestPermissionsStatement(builder: MethodSpec.Builder, targetParam: String, permissionField: String, requestCodeField: String)

    abstract fun addShouldShowRequestPermissionRationaleCondition(builder: MethodSpec.Builder, targetParam: String, permissionField: String, isPositiveCondition: Boolean = true)

    abstract fun getActivityName(targetParam: String): String

    abstract fun isDeprecated(): Boolean

    /* Begin private */

    private fun createTypeSpec(rpe: RuntimePermissionsElement, requestCodeProvider: RequestCodeProvider): TypeSpec {
        return TypeSpec.classBuilder(rpe.generatedClassName)
                .addModifiers(Modifier.FINAL)
                .addFields(createFields(rpe.needsElements, requestCodeProvider))
                .addMethod(createConstructor())
                .addMethods(createWithPermissionCheckMethods(rpe))
                .addMethods(createPermissionHandlingMethods(rpe))
                .addTypes(createPermissionRequestClasses(rpe))
                .apply {
                    if (isDeprecated()) {
                        addAnnotation(createDeprecatedAnnotation())
                    }
                }
                .build()
    }

    private fun createDeprecatedAnnotation(): AnnotationSpec {
        return AnnotationSpec.builder(java.lang.Deprecated::class.java).build()
    }

    private fun createFields(needsElements: List<ExecutableElement>, requestCodeProvider: RequestCodeProvider): List<FieldSpec> {
        val fields: ArrayList<FieldSpec> = arrayListOf()

        // The Set of annotated elements needs to be ordered
        // in order to achieve Deterministic, Reproducible Builds
        needsElements.sortedBy { it.simpleString() }.forEach {
            // For each method annotated with @NeedsPermission, add REQUEST integer and PERMISSION String[] fields
            fields.add(createRequestCodeField(it, requestCodeProvider.nextRequestCode()))
            fields.add(createPermissionField(it))

            if (it.parameters.isNotEmpty()) {
                fields.add(createPendingRequestField(it))
            }
        }
        return fields
    }

    private fun createRequestCodeField(e: ExecutableElement, index: Int): FieldSpec {
        return FieldSpec.builder(Int::class.java, requestCodeFieldName(e))
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer("\$L", index)
                .build()
    }

    private fun createPermissionField(e: ExecutableElement): FieldSpec {
        val permissionValue: List<String> = e.getAnnotation(NeedsPermission::class.java).permissionValue()
        val formattedValue: String = permissionValue.joinToString(
                separator = ",",
                prefix = "{",
                postfix = "}",
                transform = { "\"$it\"" }
        )
        return FieldSpec.builder(ArrayTypeName.of(String::class.java), permissionFieldName(e))
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer("\$N", "new String[] $formattedValue")
                .build()
    }

    private fun createPendingRequestField(e: ExecutableElement): FieldSpec {
        return FieldSpec.builder(ClassName.get("permissions.dispatcher", "GrantableRequest"), pendingRequestFieldName(e))
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .build()
    }

    private fun createConstructor(): MethodSpec {
        return MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PRIVATE)
                .build()
    }

    private fun createWithPermissionCheckMethods(rpe: RuntimePermissionsElement): List<MethodSpec> {
        val methods: ArrayList<MethodSpec> = arrayListOf()
        rpe.needsElements.forEach {
            // For each @NeedsPermission method, create the "WithPermissionCheck" equivalent
            methods.add(createWithPermissionCheckMethod(rpe, it))
        }
        return methods
    }

    private fun createWithPermissionCheckMethod(rpe: RuntimePermissionsElement, method: ExecutableElement): MethodSpec {
        val targetParam = "target"
        val builder = MethodSpec.methodBuilder(withPermissionCheckMethodName(method))
                .addTypeVariables(rpe.typeVariables)
                .addModifiers(Modifier.STATIC)
                .returns(TypeName.VOID)
                .addParameter(ParameterSpec.builder(rpe.typeName, targetParam).addAnnotation(NonNull::class.java).build())

        // If the method has parameters, add those as well
        method.parameters.forEach {
            builder.addParameter(typeNameOf(it), it.simpleString())
        }

        // Delegate method body generation to implementing classes
        addWithPermissionCheckBody(builder, method, rpe, targetParam)
        return builder.build()
    }

    fun addWithPermissionCheckBody(builder: MethodSpec.Builder, needsMethod: ExecutableElement, rpe: RuntimePermissionsElement, targetParam: String) {
        // Create field names for the constants to use
        val requestCodeField = requestCodeFieldName(needsMethod)
        val permissionField = permissionFieldName(needsMethod)

        // if maxSdkVersion is lower than os level does nothing
        val needsPermissionMaxSdkVersion = needsMethod.getAnnotation(NeedsPermission::class.java).maxSdkVersion
        if (needsPermissionMaxSdkVersion > 0) {
            builder.beginControlFlow("if (\$T.VERSION.SDK_INT > \$L)", BUILD, needsPermissionMaxSdkVersion)
                    .addCode(CodeBlock.builder()
                            .add("\$N.\$N(", targetParam, needsMethod.simpleString())
                            .add(varargsParametersCodeBlock(needsMethod))
                            .addStatement(")")
                            .addStatement("return")
                            .build())
                    .endControlFlow()
        }

        // Add the conditional for when permission has already been granted
        val needsPermissionParameter = needsMethod.getAnnotation(NeedsPermission::class.java).value[0]
        val activityVar = getActivityName(targetParam)
        ADD_WITH_CHECK_BODY_MAP[needsPermissionParameter]?.addHasSelfPermissionsCondition(builder, activityVar, permissionField)
                ?: builder.beginControlFlow("if (\$T.hasSelfPermissions(\$N, \$N))", PERMISSION_UTILS, activityVar, permissionField)
        builder.addCode(CodeBlock.builder()
                .add("\$N.\$N(", targetParam, needsMethod.simpleString())
                .add(varargsParametersCodeBlock(needsMethod))
                .addStatement(")")
                .build()
        )
        builder.nextControlFlow("else")

        // Add the conditional for "OnShowRationale", if present
        val onRationale: ExecutableElement? = rpe.findOnRationaleForNeeds(needsMethod)
        val hasParameters: Boolean = needsMethod.parameters.isNotEmpty()
        if (hasParameters) {
            // If the method has parameters, precede the potential OnRationale call with
            // an instantiation of the temporary Request object
            val varargsCall = CodeBlock.builder()
                    .add("\$N = new \$N(\$N, ",
                            pendingRequestFieldName(needsMethod),
                            permissionRequestTypeName(rpe, needsMethod),
                            targetParam
                    )
                    .add(varargsParametersCodeBlock(needsMethod))
                    .addStatement(")")
            builder.addCode(varargsCall.build())
        }
        if (onRationale != null) {
            addShouldShowRequestPermissionRationaleCondition(builder, targetParam, permissionField)
            if (hasParameters) {
                // For methods with parameters, use the PermissionRequest instantiated above
                builder.addStatement("\$N.\$N(\$N)", targetParam, onRationale.simpleString(), pendingRequestFieldName(needsMethod))
            } else {
                // Otherwise, create a new PermissionRequest on-the-fly
                builder.addStatement("\$N.\$N(new \$N(\$N))", targetParam, onRationale.simpleString(), permissionRequestTypeName(rpe, needsMethod), targetParam)
            }
            builder.nextControlFlow("else")
        }

        // Add the branch for "request permission"
        ADD_WITH_CHECK_BODY_MAP[needsPermissionParameter]?.addRequestPermissionsStatement(builder, targetParam, activityVar, requestCodeField)
                ?: addRequestPermissionsStatement(builder, targetParam, permissionField, requestCodeField)
        if (onRationale != null) {
            builder.endControlFlow()
        }
        builder.endControlFlow()
    }

    private fun createPermissionHandlingMethods(rpe: RuntimePermissionsElement): List<MethodSpec> {
        val methods: ArrayList<MethodSpec> = arrayListOf()

        if (hasNormalPermission(rpe)) {
            methods.add(createPermissionResultMethod(rpe))
        }

        if (hasSystemAlertWindowPermission(rpe) || hasWriteSettingPermission(rpe)) {
            methods.add(createOnActivityResultMethod(rpe))
        }

        return methods
    }

    private fun createOnActivityResultMethod(rpe: RuntimePermissionsElement): MethodSpec {
        val targetParam = "target"
        val requestCodeParam = "requestCode"
        val grantResultsParam = "grantResults"
        val builder = MethodSpec.methodBuilder("onActivityResult")
                .addTypeVariables(rpe.typeVariables)
                .addModifiers(Modifier.STATIC)
                .returns(TypeName.VOID)
                .addParameter(ParameterSpec.builder(rpe.typeName, targetParam).addAnnotation(NonNull::class.java).build())
                .addParameter(TypeName.INT, requestCodeParam)

        builder.beginControlFlow("switch (\$N)", requestCodeParam)
        for (needsMethod in rpe.needsElements) {
            val needsPermissionParameter = needsMethod.getAnnotation(NeedsPermission::class.java).value[0]
            if (!ADD_WITH_CHECK_BODY_MAP.containsKey(needsPermissionParameter)) {
                continue
            }

            builder.addCode("case \$N:\n", requestCodeFieldName(needsMethod))

            addResultCaseBody(builder, needsMethod, rpe, targetParam, grantResultsParam)
        }

        builder
                .addCode("default:\n")
                .addStatement("break")
                .endControlFlow()

        return builder.build()
    }

    private fun createPermissionResultMethod(rpe: RuntimePermissionsElement): MethodSpec {
        val targetParam = "target"
        val requestCodeParam = "requestCode"
        val grantResultsParam = "grantResults"
        val builder = MethodSpec.methodBuilder("onRequestPermissionsResult")
                .addTypeVariables(rpe.typeVariables)
                .addModifiers(Modifier.STATIC)
                .returns(TypeName.VOID)
                .addParameter(ParameterSpec.builder(rpe.typeName, targetParam).addAnnotation(NonNull::class.java).build())
                .addParameter(TypeName.INT, requestCodeParam)
                .addParameter(ArrayTypeName.of(TypeName.INT), grantResultsParam)

        // For each @NeedsPermission method, add a switch case
        builder.beginControlFlow("switch (\$N)", requestCodeParam)
        for (needsMethod in rpe.needsElements) {
            val needsPermissionParameter = needsMethod.getAnnotation(NeedsPermission::class.java).value[0]
            if (ADD_WITH_CHECK_BODY_MAP.containsKey(needsPermissionParameter)) {
                continue
            }

            builder.addCode("case \$N:\n", requestCodeFieldName(needsMethod))

            // Delegate switch-case generation to implementing classes
            addResultCaseBody(builder, needsMethod, rpe, targetParam, grantResultsParam)
        }

        // Add the default case
        builder
                .addCode("default:\n")
                .addStatement("break")
                .endControlFlow()

        return builder.build()
    }

    private fun addResultCaseBody(builder: MethodSpec.Builder, needsMethod: ExecutableElement, rpe: RuntimePermissionsElement, targetParam: String, grantResultsParam: String) {

        // just workaround, see https://github.com/hotchemi/PermissionsDispatcher/issues/45
        val onDenied: ExecutableElement? = rpe.findOnDeniedForNeeds(needsMethod)
        val hasDenied = onDenied != null
        val needsPermissionParameter = needsMethod.getAnnotation(NeedsPermission::class.java).value[0]
        val permissionField = permissionFieldName(needsMethod)

        // Add the conditional for "permission verified"
        ADD_WITH_CHECK_BODY_MAP[needsPermissionParameter]?.addHasSelfPermissionsCondition(builder, getActivityName(targetParam), permissionField)
                ?: builder.beginControlFlow("if (\$T.verifyPermissions(\$N))", PERMISSION_UTILS, grantResultsParam)

        // Based on whether or not the method has parameters, delegate to the "pending request" object or invoke the method directly
        val hasParameters = needsMethod.parameters.isNotEmpty()
        if (hasParameters) {
            val pendingField = pendingRequestFieldName(needsMethod)
            builder.beginControlFlow("if (\$N != null)", pendingField)
            builder.addStatement("\$N.grant()", pendingField)
            builder.endControlFlow()
        } else {
            builder.addStatement("target.\$N()", needsMethod.simpleString())
        }

        // Add the conditional for "permission denied" and/or "never ask again", if present
        val onNeverAsk: ExecutableElement? = rpe.findOnNeverAskForNeeds(needsMethod)
        val hasNeverAsk = onNeverAsk != null

        if (hasDenied || hasNeverAsk) {
            builder.nextControlFlow("else")
        }
        if (hasNeverAsk) {
            // Split up the "else" case with another if condition checking for "never ask again" first
            addShouldShowRequestPermissionRationaleCondition(builder, targetParam, permissionFieldName(needsMethod), false)
            builder.addStatement("target.\$N()", onNeverAsk!!.simpleString())

            // If a "permission denied" is present as well, go into an else case, otherwise close this temporary branch
            if (hasDenied) {
                builder.nextControlFlow("else")
            } else {
                builder.endControlFlow()
            }
        }
        if (hasDenied) {
            // Add the "permissionDenied" statement
            builder.addStatement("\$N.\$N()", targetParam, onDenied!!.simpleString())

            // Close the additional control flow potentially opened by a "never ask again" method
            if (hasNeverAsk) {
                builder.endControlFlow()
            }
        }
        // Close the "switch" control flow
        builder.endControlFlow()

        // Remove the temporary pending request field, in case it was used for a method with parameters
        if (hasParameters) {
            builder.addStatement("\$N = null", pendingRequestFieldName(needsMethod))
        }
        builder.addStatement("break")
    }

    private fun hasNormalPermission(rpe: RuntimePermissionsElement): Boolean {
        rpe.needsElements.forEach {
            val permissionValue: List<String> = it.getAnnotation(NeedsPermission::class.java).permissionValue()
            if (!permissionValue.contains(MANIFEST_SYSTEM_ALERT_WINDOW) && !permissionValue.contains(MANIFEST_WRITE_SETTING)) {
                return true
            }
        }
        return false
    }

    private fun hasSystemAlertWindowPermission(rpe: RuntimePermissionsElement): Boolean {
        return isDefinePermission(rpe, MANIFEST_SYSTEM_ALERT_WINDOW)
    }

    private fun hasWriteSettingPermission(rpe: RuntimePermissionsElement): Boolean {
        return isDefinePermission(rpe, MANIFEST_WRITE_SETTING)
    }

    private fun isDefinePermission(rpe: RuntimePermissionsElement, permissionName: String): Boolean {
        rpe.needsElements.forEach {
            val permissionValue: List<String> = it.getAnnotation(NeedsPermission::class.java).permissionValue()
            if (permissionValue.contains(permissionName)) {
                return true
            }
        }
        return false
    }

    private fun createPermissionRequestClasses(rpe: RuntimePermissionsElement): List<TypeSpec> {
        val classes: ArrayList<TypeSpec> = arrayListOf()
        rpe.needsElements.forEach {
            val onRationale = rpe.findOnRationaleForNeeds(it)
            if (onRationale != null || it.parameters.isNotEmpty()) {
                classes.add(createPermissionRequestClass(rpe, it))
            }
        }
        return classes
    }

    private fun createPermissionRequestClass(rpe: RuntimePermissionsElement, needsMethod: ExecutableElement): TypeSpec {
        // Select the superinterface of the generated class
        // based on whether or not the annotated method has parameters
        val hasParameters: Boolean = needsMethod.parameters.isNotEmpty()
        val superInterfaceName: String = if (hasParameters) "GrantableRequest" else "PermissionRequest"

        val targetType = rpe.typeName
        val builder = TypeSpec.classBuilder(permissionRequestTypeName(rpe, needsMethod))
                .addTypeVariables(rpe.typeVariables)
                .addSuperinterface(ClassName.get("permissions.dispatcher", superInterfaceName))
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)

        // Add required fields to the target
        val weakFieldName: String = "weakTarget"
        val weakFieldType = ParameterizedTypeName.get(ClassName.get("java.lang.ref", "WeakReference"), targetType)
        builder.addField(weakFieldType, weakFieldName, Modifier.PRIVATE, Modifier.FINAL)
        needsMethod.parameters.forEach {
            builder.addField(typeNameOf(it), it.simpleString(), Modifier.PRIVATE, Modifier.FINAL)
        }

        // Add constructor
        val targetParam = "target"
        val constructorBuilder = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PRIVATE)
                .addParameter(ParameterSpec.builder(targetType, targetParam).addAnnotation(NonNull::class.java).build())
                .addStatement("this.\$L = new WeakReference<\$T>(\$N)", weakFieldName, targetType, targetParam)
        needsMethod.parameters.forEach {
            val fieldName = it.simpleString()
            constructorBuilder
                    .addParameter(typeNameOf(it), fieldName)
                    .addStatement("this.\$L = \$N", fieldName, fieldName)
        }
        builder.addMethod(constructorBuilder.build())

        // Add proceed() override
        val proceedMethod: MethodSpec.Builder = MethodSpec.methodBuilder("proceed")
                .addAnnotation(Override::class.java)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.VOID)
                .addStatement("\$T target = \$N.get()", targetType, weakFieldName)
                .addStatement("if (target == null) return")
        val requestCodeField = requestCodeFieldName(needsMethod)
        ADD_WITH_CHECK_BODY_MAP[needsMethod.getAnnotation(NeedsPermission::class.java).value[0]]?.addRequestPermissionsStatement(proceedMethod, targetParam, getActivityName(targetParam), requestCodeField)
                ?: addRequestPermissionsStatement(proceedMethod, targetParam, permissionFieldName(needsMethod), requestCodeField)
        builder.addMethod(proceedMethod.build())

        // Add cancel() override method
        val cancelMethod: MethodSpec.Builder = MethodSpec.methodBuilder("cancel")
                .addAnnotation(Override::class.java)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.VOID)
        val onDenied = rpe.findOnDeniedForNeeds(needsMethod)
        if (onDenied != null) {
            cancelMethod
                    .addStatement("\$T target = \$N.get()", targetType, weakFieldName)
                    .addStatement("if (target == null) return")
                    .addStatement("target.\$N()", onDenied.simpleString())
        }
        builder.addMethod(cancelMethod.build())

        // For classes with additional parameters, add a "grant()" method
        if (hasParameters) {
            val grantMethod: MethodSpec.Builder = MethodSpec.methodBuilder("grant")
                    .addAnnotation(Override::class.java)
                    .addModifiers(Modifier.PUBLIC)
                    .returns(TypeName.VOID)
            grantMethod
                    .addStatement("\$T target = \$N.get()", targetType, weakFieldName)
                    .addStatement("if (target == null) return")

            // Compose the call to the permission-protected method;
            // since the number of parameters is variable, utilize the low-level CodeBlock here
            // to compose the method call and its parameters
            grantMethod.addCode(
                    CodeBlock.builder()
                            .add("target.\$N(", needsMethod.simpleString())
                            .add(varargsParametersCodeBlock(needsMethod))
                            .addStatement(")")
                            .build()
            )
            builder.addMethod(grantMethod.build())
        }

        return builder.build()
    }
}
