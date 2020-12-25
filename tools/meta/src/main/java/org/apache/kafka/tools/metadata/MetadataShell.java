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

import org.jline.reader.EndOfFileException;
import org.jline.reader.History;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.ParsedLine;
import org.jline.reader.Parser;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.DefaultParser;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.util.AbstractMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * The Kafka metadata shell.
 */
public final class MetadataShell implements AutoCloseable {
    private final Terminal terminal;
    private final Parser parser;
    private final History history;
    private final LineReader reader;

    public MetadataShell() throws IOException {
        TerminalBuilder builder = TerminalBuilder.builder().
            system(true).
            nativeSignals(true);
        this.terminal = builder.build();
        this.parser = new DefaultParser();
        this.history = new DefaultHistory();
        this.reader = LineReaderBuilder.builder().
            terminal(terminal).
            parser(parser).
            history(history).
            option(LineReader.Option.AUTO_FRESH_LINE, false).
            build();
    }

    public void runMainLoop(MetadataNodeManager nodeManager) throws Exception {
        terminal.writer().println("[ Kafka Metadata Shell ]");
        terminal.flush();
        while (true) {
            try {
                reader.readLine(">> ");
                ParsedLine parsedLine = reader.getParsedLine();
                Command.Handler handler = Command.parseCommand(parsedLine.words());
                handler.run(Optional.of(this), terminal.writer(), nodeManager);
                terminal.writer().flush();
            } catch (UserInterruptException eof) {
                // Handle ths user pressing Control-C.
                // TODO: how can we print this on the same line as the prompt like
                // bash does?
                terminal.writer().println("^C");
            } catch (EndOfFileException eof) {
                return;
            }
        }
    }

    public int screenWidth() {
        return terminal.getWidth();
    }

    public Iterator<Entry<Integer, String>> history(int numEntriesToShow) {
        if (numEntriesToShow < 0) {
            numEntriesToShow = 0;
        }
        int last = history.last();
        if (numEntriesToShow > last + 1) {
            numEntriesToShow = last + 1;
        }
        int first = last - numEntriesToShow + 1;
        if (first < history.first()) {
            first = history.first();
        }
        return new HistoryIterator(first, last);
    }

    public class HistoryIterator implements  Iterator<Entry<Integer, String>> {
        private int index;
        private int last;

        HistoryIterator(int index, int last) {
            this.index = index;
            this.last = last;
        }

        @Override
        public boolean hasNext() {
            return index <= last;
        }

        @Override
        public Entry<Integer, String> next() {
            if (index > last) {
                throw new NoSuchElementException();
            }
            int p = index++;
            return new AbstractMap.SimpleImmutableEntry<>(p, history.get(p));
        }
    }

    @Override
    public void close() throws IOException {
        terminal.close();
    }
}
