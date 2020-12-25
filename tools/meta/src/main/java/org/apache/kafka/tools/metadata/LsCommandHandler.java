/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kafka.tools.metadata;

import org.apache.kafka.tools.metadata.MetadataNode.DirectoryNode;
import org.apache.kafka.tools.metadata.MetadataNode.FileNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Implements the ls command.
 */
public final class LsCommandHandler implements Command.Handler {
    private static final Logger log = LoggerFactory.getLogger(LsCommandHandler.class);

    private final List<String> targets;

    public LsCommandHandler(List<String> targets) {
        this.targets = targets;
    }

    static class TargetDirectory {
        private final String name;
        private final List<String> children;

        TargetDirectory(String name, List<String> children) {
            this.name = name;
            this.children = children;
        }
    }

    @Override
    public void run(Optional<MetadataShell> shell,
                    PrintWriter writer,
                    MetadataNodeManager manager) throws Exception {
        List<String> effectiveTargets = targets.size() == 0 ?
            Collections.singletonList(".") : targets;
        List<String> targetFiles = new ArrayList<>();
        List<TargetDirectory> targetDirectories = new ArrayList<>();
        for (String target : effectiveTargets) {
            manager.visit(new GlobVisitor(target, entryOption -> {
                if (entryOption.isPresent()) {
                    MetadataNode node = entryOption.get().node();
                    if (node instanceof DirectoryNode) {
                        DirectoryNode directory = (DirectoryNode) node;
                        List<String> children = new ArrayList<>();
                        children.addAll(directory.children().keySet());
                        targetDirectories.add(new TargetDirectory(target, children));
                    } else if (node instanceof FileNode) {
                        targetFiles.add(target);
                    }
                } else {
                    writer.println("ls: " + target + ": no such file or directory.");
                }
            }));
        }
        OptionalInt screenWidth = shell.isPresent() ?
            OptionalInt.of(shell.get().screenWidth()) : OptionalInt.empty();
        log.trace("LS : targetFiles = {}, targetDirectories = {}, screenWidth = {}",
            targetFiles, targetDirectories, screenWidth);
        printEntries(writer, "", screenWidth, targetFiles);
        boolean needIntro = targetFiles.size() > 0 || targetDirectories.size() > 1;
        boolean firstIntro = targetFiles.isEmpty();
        for (TargetDirectory targetDirectory : targetDirectories) {
            String intro = "";
            if (needIntro) {
                if (!firstIntro) {
                    intro = intro + String.format("%n");
                }
                intro = intro + targetDirectory.name + ":";
                firstIntro = false;
            }
            log.trace("LS : targetDirectory name = {}, children = {}",
                targetDirectory.name, targetDirectory.children);
            printEntries(writer, intro, screenWidth, targetDirectory.children);
        }
    }

    private void printEntries(PrintWriter writer,
                              String intro,
                              OptionalInt screenWidth,
                              List<String> entries) {
        if (entries.isEmpty()) {
            return;
        }
        if (!intro.isEmpty()) {
            writer.println(intro);
        }
        ColumnSchema columnSchema = calculateColumnSchema(screenWidth, entries);
        int numColumns = columnSchema.numColumns();
        int numLines = (entries.size() + numColumns - 1) / numColumns;
        for (int line = 0; line < numLines; line++) {
            StringBuilder output = new StringBuilder();
            for (int column = 0; column < numColumns; column++) {
                int entryIndex = line + (column * columnSchema.entriesPerColumn());
                if (entryIndex < entries.size()) {
                    String entry = entries.get(entryIndex);
                    output.append(entry);
                    if (column < numColumns - 1) {
                        int width = columnSchema.columnWidth(column);
                        for (int i = 0; i < width - entry.length() + 2; i++) {
                            output.append(" ");
                        }
                    }
                }
            }
            writer.println(output.toString());
        }
    }

    private ColumnSchema calculateColumnSchema(OptionalInt screenWidth,
                                               List<String> entries) {
        if (!screenWidth.isPresent()) {
            return new ColumnSchema(1, entries.size());
        }
        int maxColumns = screenWidth.getAsInt() / 4;
        if (maxColumns <= 1) {
            return new ColumnSchema(1, entries.size());
        }
        ColumnSchema[] schemas = new ColumnSchema[maxColumns];
        for (int numColumns = 1; numColumns <= maxColumns; numColumns++) {
            schemas[numColumns - 1] = new ColumnSchema(numColumns,
                (entries.size() + numColumns - 1) / numColumns);
        }
        for (int i = 0; i < entries.size(); i++) {
            String entry = entries.get(i);
            for (int s = 0; s < schemas.length; s++) {
                ColumnSchema schema = schemas[s];
                schema.process(i, entry);
            }
        }
        for (int s = schemas.length - 1; s > 0; s--) {
            ColumnSchema schema = schemas[s];
            if (schema.totalWidth() <= screenWidth.getAsInt()) {
                return schema;
            }
        }
        return schemas[0];
    }

    static class ColumnSchema {
        private final int[] columnWidths;
        private final int entriesPerColumn;

        ColumnSchema(int numColumns, int entriesPerColumn) {
            this.columnWidths = new int[numColumns];
            this.entriesPerColumn = entriesPerColumn;
        }

        void process(int entryIndex, String output) {
            int columnIndex = entryIndex / entriesPerColumn;
            columnWidths[columnIndex] = Math.max(
                columnWidths[columnIndex], output.length() + 2);
        }

        int totalWidth() {
            int total = 0;
            for (int i = 0; i < columnWidths.length; i++) {
                total += columnWidths[i];
            }
            return total;
        }

        int numColumns() {
            return columnWidths.length;
        }

        int columnWidth(int columnIndex) {
            return columnWidths[columnIndex];
        }

        int entriesPerColumn() {
            return entriesPerColumn;
        }
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(targets);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof LsCommandHandler)) return false;
        LsCommandHandler o = (LsCommandHandler) other;
        if (!Objects.equals(o.targets, targets)) return false;
        return true;
    }
}
