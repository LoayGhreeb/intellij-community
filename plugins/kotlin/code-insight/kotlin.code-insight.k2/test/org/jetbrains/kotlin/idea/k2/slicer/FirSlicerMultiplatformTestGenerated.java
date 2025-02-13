// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.idea.k2.slicer;

import com.intellij.testFramework.TestDataPath;
import org.jetbrains.kotlin.idea.base.plugin.KotlinPluginMode;
import org.jetbrains.kotlin.idea.base.test.TestRoot;
import org.jetbrains.kotlin.idea.test.JUnit3RunnerWithInners;
import org.jetbrains.kotlin.idea.test.KotlinTestUtils;
import org.jetbrains.kotlin.test.TestMetadata;
import org.junit.runner.RunWith;

/**
 * This class is generated by {@link org.jetbrains.kotlin.testGenerator.generator.TestGenerator}.
 * DO NOT MODIFY MANUALLY.
 */
@SuppressWarnings("all")
@TestRoot("code-insight/kotlin.code-insight.k2")
@TestDataPath("$CONTENT_ROOT")
@RunWith(JUnit3RunnerWithInners.class)
@TestMetadata("../../idea/tests/testData/slicer/mpp")
public class FirSlicerMultiplatformTestGenerated extends AbstractFirSlicerMultiplatformTest {
    @java.lang.Override
    @org.jetbrains.annotations.NotNull
    public final KotlinPluginMode getPluginMode() {
        return KotlinPluginMode.K2;
    }

    private void runTest(String testDataFilePath) throws Exception {
        KotlinTestUtils.runTest(this::doTest, this, testDataFilePath);
    }

    @TestMetadata("actualClassFunctionParameter")
    public void testActualClassFunctionParameter() throws Exception {
        runTest("../../idea/tests/testData/slicer/mpp/actualClassFunctionParameter/");
    }

    @TestMetadata("actualFunctionParameter")
    public void testActualFunctionParameter() throws Exception {
        runTest("../../idea/tests/testData/slicer/mpp/actualFunctionParameter/");
    }

    @TestMetadata("expectClassFunctionParameter")
    public void testExpectClassFunctionParameter() throws Exception {
        runTest("../../idea/tests/testData/slicer/mpp/expectClassFunctionParameter/");
    }

    @TestMetadata("expectExtensionFunctionResultOut")
    public void testExpectExtensionFunctionResultOut() throws Exception {
        runTest("../../idea/tests/testData/slicer/mpp/expectExtensionFunctionResultOut/");
    }

    @TestMetadata("expectFunctionParameter")
    public void testExpectFunctionParameter() throws Exception {
        runTest("../../idea/tests/testData/slicer/mpp/expectFunctionParameter/");
    }

    @TestMetadata("expectFunctionResultIn")
    public void testExpectFunctionResultIn() throws Exception {
        runTest("../../idea/tests/testData/slicer/mpp/expectFunctionResultIn/");
    }

    @TestMetadata("expectFunctionResultOut")
    public void testExpectFunctionResultOut() throws Exception {
        runTest("../../idea/tests/testData/slicer/mpp/expectFunctionResultOut/");
    }

    @TestMetadata("expectPropertyResultIn")
    public void testExpectPropertyResultIn() throws Exception {
        runTest("../../idea/tests/testData/slicer/mpp/expectPropertyResultIn/");
    }

    @TestMetadata("expectPropertyResultOut")
    public void testExpectPropertyResultOut() throws Exception {
        runTest("../../idea/tests/testData/slicer/mpp/expectPropertyResultOut/");
    }
}
