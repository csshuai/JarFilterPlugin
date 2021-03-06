/*
 * Copyright 2018. nekocode (nekocode.cn@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cn.nekocode.jarfilter

import com.android.annotations.NonNull
import com.android.build.api.transform.*
import com.android.build.gradle.internal.pipeline.TransformManager
import com.android.utils.FileUtils
import com.google.common.collect.ImmutableSet
import com.google.common.collect.Sets
import com.google.common.io.ByteStreams
import org.gradle.api.Project

import java.util.function.Predicate
import java.util.regex.Pattern
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * @author nekocode (nekocode.cn@gmail.com)
 */
class JarFilterTransform extends Transform {
    private final Project project
    private final File listFile


    JarFilterTransform(Project project) {
        this.project = project
        listFile = new File(
                project.getBuildDir(), UpdateFilterListFileTask.FILTER_FILE_NAME)
    }

    @NonNull
    @Override
    String getName() {
        return "jarFilter"
    }

    @NonNull
    @Override
    Set<? super QualifiedContent.Scope> getScopes() {
        return Sets.immutableEnumSet(QualifiedContent.Scope.EXTERNAL_LIBRARIES)
    }

    @NonNull
    @Override
    Set<QualifiedContent.ContentType> getInputTypes() {
        return TransformManager.CONTENT_CLASS
    }

    @NonNull
    @Override
    Collection<SecondaryFile> getSecondaryFiles() {
        return ImmutableSet.of(
                SecondaryFile.nonIncremental(project.files(listFile))
        )
    }

    @Override
    boolean isIncremental() {
        return true
    }

    @Override
    void transform(TransformInvocation invocation)
            throws TransformException, InterruptedException, IOException {

        final TransformOutputProvider outputProvider = invocation.getOutputProvider()
        assert outputProvider != null

        final List<Pattern> filterList =
                listFile.collect().stream()
                        .filter { line -> !line.isAllWhitespace() }
                        .map { str -> Pattern.compile(str) }
                        .collect()
        final Predicate<String> skipFilter = { path ->
            for (Pattern pattern : filterList) {
                if (pattern.matcher(path).matches()) {
                    return true
                }
            }

            return false
        }

        if (!invocation.isIncremental()) {
            outputProvider.deleteAll()
        }

        invocation.inputs.each { input ->
            input.jarInputs.each { jarInput ->
                final File outJar = outputProvider.getContentLocation(
                        jarInput.name,
                        jarInput.contentTypes,
                        jarInput.scopes,
                        Format.JAR
                )

                if (invocation.isIncremental()) {
                    switch (jarInput.status) {
                        case Status.NOTCHANGED:
                            break
                        case Status.ADDED:
                        case Status.CHANGED:
                            copyAndFilterJar(jarInput.file, outJar, skipFilter)
                            break
                        case Status.REMOVED:
                            FileUtils.deleteIfExists(outJar)
                            break
                    }

                } else {
                    copyAndFilterJar(jarInput.file, outJar, skipFilter)
                }
            }
        }
    }

    private static void copyAndFilterJar(
            File inJarFile, File outJarFile, Predicate<String> skipFilter) {

        new ZipInputStream(new FileInputStream(inJarFile)).withCloseable { zis ->
            new ZipOutputStream(new FileOutputStream(outJarFile)).withCloseable { zos ->

                ZipEntry entry
                while ((entry = zis.getNextEntry()) != null) {
                    if (skipFilter.test(entry.getName())) {
                        // Skip this file's copying
                        continue
                    }

                    zos.putNextEntry(entry)
                    ByteStreams.copy(zis, zos)
                    zos.closeEntry()
                }
            }
        }
    }
}