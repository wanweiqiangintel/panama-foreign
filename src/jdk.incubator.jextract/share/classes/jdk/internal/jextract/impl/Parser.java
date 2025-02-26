/*
 *  Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
 *  DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 *  This code is free software; you can redistribute it and/or modify it
 *  under the terms of the GNU General Public License version 2 only, as
 *  published by the Free Software Foundation.  Oracle designates this
 *  particular file as subject to the "Classpath" exception as provided
 *  by Oracle in the LICENSE file that accompanied this code.
 *
 *  This code is distributed in the hope that it will be useful, but WITHOUT
 *  ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 *  FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 *  version 2 for more details (a copy is included in the LICENSE file that
 *  accompanied this code).
 *
 *  You should have received a copy of the GNU General Public License version
 *  2 along with this work; if not, write to the Free Software Foundation,
 *  Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 *   Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 *  or visit www.oracle.com if you need additional information or have any
 *  questions.
 *
 */
package jdk.internal.jextract.impl;

import jdk.incubator.jextract.Declaration;
import jdk.internal.clang.Cursor;
import jdk.internal.clang.CursorKind;
import jdk.internal.clang.Diagnostic;
import jdk.internal.clang.Index;
import jdk.internal.clang.LibClang;
import jdk.internal.clang.SourceLocation;
import jdk.internal.clang.SourceRange;
import jdk.internal.clang.TranslationUnit;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public class Parser {
    private final TreeMaker treeMaker;

    public Parser() {
        this.treeMaker = new TreeMaker();
    }

    public Declaration.Scoped parse(Path path, Collection<String> args) {
        final Index index = LibClang.createIndex(false);

        TranslationUnit tu = index.parse(path.toString(),
            d -> {
                if (d.severity() > Diagnostic.CXDiagnostic_Warning) {
                    throw new ClangException(d.toString());
                }
            },
            true, args.toArray(new String[0]));

        MacroParserImpl macroParser = MacroParserImpl.make(treeMaker, tu, args);

        List<Declaration> decls = new ArrayList<>();
        Cursor tuCursor = tu.getCursor();
        tuCursor.children().
            forEach(c -> {
                SourceLocation loc = c.getSourceLocation();
                if (loc == null) {
                    return;
                }

                SourceLocation.Location src = loc.getFileLocation();
                if (src == null) {
                    return;
                }


                if (c.isDeclaration()) {
                    if (c.kind() == CursorKind.UnexposedDecl ||
                        c.kind() == CursorKind.Namespace) {
                        c.children().map(treeMaker::createTree)
                                .filter(t -> t != null)
                                .forEach(decls::add);
                    } else {
                        Declaration decl = treeMaker.createTree(c);
                        if (decl != null) {
                            decls.add(decl);
                        }
                    }
                } else if (isMacro(c) && src.path() != null) {
                    SourceRange range = c.getExtent();
                    String[] tokens = c.getTranslationUnit().tokens(range);
                    Optional<Declaration.Constant> constant = macroParser.parseConstant(TreeMaker.CursorPosition.of(c), c.spelling(), tokens);
                    if (constant.isPresent()) {
                        decls.add(constant.get());
                    }
                }
            });

        decls.addAll(macroParser.macroTable.reparseConstants());
        Declaration.Scoped rv = treeMaker.createHeader(tuCursor, decls);
        treeMaker.freeze();
        index.close();
        return rv;
    }

    private boolean isMacro(Cursor c) {
        return c.isPreprocessing() && c.kind() == CursorKind.MacroDefinition;
    }
}
