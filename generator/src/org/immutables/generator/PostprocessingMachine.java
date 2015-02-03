package org.immutables.generator;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.TreeSet;

final class PostprocessingMachine {
  private static final Joiner JOINER = Joiner.on("");

  private PostprocessingMachine() {
  }

  static CharSequence rewrite(CharSequence content) {
    String currentPackage = "";
    ImportsBuilder importsBuilder = new ImportsBuilder();

    State state = State.UNDEFINED;
    int packageFrom = -1;
    int importFrom = -1;
    int nextPartFrom = 0;
    boolean importStarts = false;
    int classNameOccurrencesInImportBlock = 0;
    int classNameFrom = -1;
    int classNameTo = -1;
    FiniteStateMachine machine = new FiniteStateMachine();
    FullyQualifiedNameMachine fullyNameMachine = new FullyQualifiedNameMachine();
    CommentMachine commentMachine = new CommentMachine();
    ClassNameMachine nameMachine = new ClassNameMachine();

    for (int i = 0; i < content.length(); i++) {
      char c = content.charAt(i);

      switch (state) {
      case UNDEFINED:
        state = machine.nextChar(c).or(state);
        break;
      case PACKAGE:
        if (c == ' ') {
          packageFrom = i + 1;
        }
        if (c == ';') {
          nextPartFrom = i + 2;
          currentPackage = content.subSequence(packageFrom, i).toString();
          importsBuilder.setCurrentPackage(currentPackage);
          state = State.UNDEFINED;
          packageFrom = -1;
        }
        break;
      case IMPORTS:
        nameMachine.nextChar(c, i);
        if (nameMachine.isFound()) {
          classNameOccurrencesInImportBlock++;
          classNameFrom = nameMachine.classNameFrom;
          classNameTo = nameMachine.classNameTo;
        }
        if (!importStarts && c == ' ') {
          importFrom = i + 1;
          importStarts = true;
        }
        if (c == ';') {
          nextPartFrom = i + 2;
          importsBuilder.addImport(content.subSequence(importFrom, i).toString());
          state = State.UNDEFINED;
          importFrom = -1;
          importStarts = false;
          if (classNameOccurrencesInImportBlock == 1) {
            importsBuilder.addToStopList(content.subSequence(classNameFrom, classNameTo).toString());
            nameMachine.reset();
          }
        }
        break;
      case CLASS:
        commentMachine.nextChar(c);
        if (!commentMachine.isInComment()) {
          nameMachine.nextChar(c, i);
          fullyNameMachine.nextChar(c, i);
          if (fullyNameMachine.isFinished()) {
            importsBuilder.addImportCandidate(
                content.subSequence(fullyNameMachine.packageTo, fullyNameMachine.importTo).toString(),
                content.subSequence(fullyNameMachine.importFrom, fullyNameMachine.importTo).toString(),
                fullyNameMachine.importFrom,
                fullyNameMachine.importTo,
                fullyNameMachine.packageTo);
            fullyNameMachine.reset();
          }
          if (fullyNameMachine.state.equals(FullyQualifiedNameState.CLASS)) {
            nameMachine.reset();
          }
          if (nameMachine.isFound()) {
            importsBuilder.addException(
                content.subSequence(nameMachine.classNameFrom, nameMachine.classNameTo).toString());
          }
        }
        break;
      }
    }

    importsBuilder.preBuild();

    StringBuilder stringBuilder = new StringBuilder(content.length() << 1);

    for (ImportCandidate importCandidate : importsBuilder.importCandidates.values()) {
      if (importCandidate.importTo != -1) {
        importsBuilder.addImport(importCandidate.preparedImport);
      }
      stringBuilder.append(content.subSequence(nextPartFrom, importCandidate.importFrom));
      nextPartFrom = importCandidate.packageTo;
    }

    String imports = importsBuilder.build();

    stringBuilder
        // last part
        .append(content.subSequence(nextPartFrom, content.length()))
            // imports
        .insert(0, imports);

    // package
    if (!currentPackage.isEmpty()) {
      stringBuilder.insert(0, ";\n").insert(0, currentPackage).insert(0, "package ");
    }

    return stringBuilder.toString();
  }

  enum State {
    UNDEFINED,
    PACKAGE,
    IMPORTS,
    CLASS
  }

  static final class FiniteStateMachine {
    private static final char[][] vocabulary = new char[][] {
        {'p', 'a', 'c', 'k', 'a', 'g', 'e'},
        {'i', 'm', 'p', 'o', 'r', 't'},
        {'c', 'l', 'a', 's', 's'}
    };

    private static final State[] finalState = new State[] {
        State.PACKAGE,
        State.IMPORTS,
        State.CLASS
    };

    int wordIndex = -1;
    int charIndex = -1;

    Optional<State> nextChar(char c) {
      Optional<State> state = Optional.absent();

      if (wordIndex == -2) {

        if (!isAlphabetic(c) && !isDigit(c)) {
          wordIndex = -1;
        }

      } else if (wordIndex == -1) {

        for (int i = 0; i < vocabulary.length; i++) {
          if (c == vocabulary[i][0]) {
            wordIndex = i;
            charIndex = 0;
            break;
          }
        }

        if (wordIndex == -1 && (isAlphabetic(c) || isDigit(c))) {
          wordIndex = -2;
        }

      } else {

        if (vocabulary[wordIndex][charIndex + 1] == c) {
          charIndex++;
          if (vocabulary[wordIndex].length == charIndex + 1) {
            state = Optional.of(finalState[wordIndex]);
            wordIndex = -1;
            charIndex = -1;
          }
        } else {
          wordIndex = -1;
          charIndex = -1;
        }

      }

      return state;
    }
  }

  static final class ImportsBuilder {
    private static final String JAVA_LANG = "java.lang";

    private TreeSet<String> imports = Sets.newTreeSet();
    private Optional<String> currentPackage = Optional.absent();
    private LinkedHashMap<String, ImportCandidate> importCandidates = Maps.newLinkedHashMap();
    private HashSet<String> exceptions = Sets.newHashSet();
    private HashSet<String> stopList = Sets.newHashSet();

    void addImportCandidate(String name, String fullyQualifiedName, int importFrom, int importTo, int packageTo) {
      if (fullyQualifiedName.startsWith(JAVA_LANG)) {
        importCandidates.put(name, new ImportCandidate(importFrom, -1, packageTo, fullyQualifiedName));
        return;
      }

      if (currentPackage.isPresent() && fullyQualifiedName.startsWith(currentPackage.get())) {
        importCandidates.put(name, new ImportCandidate(importFrom, -1, packageTo, fullyQualifiedName));
        return;
      }

      importCandidates.put(name, new ImportCandidate(importFrom, importTo, packageTo, fullyQualifiedName));
    }

    void addToStopList(String name) {
      stopList.add(name);
    }

    void addException(String name) {
      if (!stopList.contains(name)) {
        exceptions.add(name);
      }
    }

    void addImport(String importedPackage) {
      imports.add(importedPackage);
    }

    void setCurrentPackage(String currentPackage) {
      this.currentPackage = Optional.of(currentPackage);
    }

    void preBuild() {
      for (String exception : exceptions) {
        importCandidates.remove(exception);
      }
    }

    String build() {
      return JOINER.join(Iterables.transform(imports, ToImportStatement.FUNCTION));
    }
  }

  private enum ToImportStatement implements Function<String, String> {
    FUNCTION;

    @Override
    public String apply(String input) {
      return "import " + input + ";\n";
    }
  }

  static final class FullyQualifiedNameMachine {
    FullyQualifiedNameState state = FullyQualifiedNameState.UNDEFINED;
    int importFrom = -1;
    int importTo = -1;
    int packageTo = -1;

    void nextChar(char c, int i) {
      switch (state) {
      case UNDEFINED:
        if (isLowerCaseAlphabetic(c)) {
          state = FullyQualifiedNameState.PACKAGE_PART_CANDIDATE;
          importFrom = i;
        }
        break;
      case PACKAGE_PART_CANDIDATE:
        if (c == '.') {
          state = FullyQualifiedNameState.DOT;
        } else if (!isAlphabetic(c) && !isDigit(c)) {
          reset();
        }
        break;
      case DOT:
        if (isLowerCaseAlphabetic(c)) {
          state = FullyQualifiedNameState.PACKAGE_PART_CANDIDATE;
        } else if (isUpperCaseAlphabetic(c)) {
          state = FullyQualifiedNameState.CLASS;
        } else {
          reset();
        }
        break;
      case CLASS:
        if (packageTo == -1) {
          packageTo = i - 1;
        }
        if (isSeparator(c)) {
          state = FullyQualifiedNameState.FINISH;
          importTo = i;
        } else if (!isAlphabetic(c) && !isDigit(c)) {
          state = FullyQualifiedNameState.AFTER_CLASS;
        }
        break;
      case AFTER_CLASS:
        if (importTo == -1) {
          importTo = i - 1;
        }
        if (isAlphabetic(c)) {
          state = FullyQualifiedNameState.METHOD_OR_FIELD;
        } else if (!isAlphabetic(c) && !isDigit(c)) {
          state = FullyQualifiedNameState.FINISH;
        }
        break;
      case METHOD_OR_FIELD:
        if (!isAlphabetic(c) && !isDigit(c)) {
          state = FullyQualifiedNameState.FINISH;
        }
        break;
      case FINISH:
        reset();
        break;
      }
    }

    boolean isFinished() {
      return FullyQualifiedNameState.FINISH.equals(state);
    }

    void reset() {
      state = FullyQualifiedNameState.UNDEFINED;
      importFrom = -1;
      importTo = -1;
      packageTo = -1;
    }
  }

  enum FullyQualifiedNameState {
    UNDEFINED,
    PACKAGE_PART_CANDIDATE,
    DOT,
    CLASS,
    AFTER_CLASS,
    METHOD_OR_FIELD,
    FINISH
  }

  static final class CommentMachine {

    CommentState state = CommentState.NOT_IN_COMMENT;

    void nextChar(char c) {
      switch (state) {
      case NOT_IN_COMMENT:
        if (c == '/') {
          state = CommentState.COMMENT_CANDIDATE;
        }
        break;
      case COMMENT_CANDIDATE:
        if (c == '/') {
          state = CommentState.LINE_COMMENT;
        } else if (c == '*') {
          state = CommentState.BLOCK_COMMENT;
        }
        break;
      case LINE_COMMENT:
        if (c == '\n') {
          state = CommentState.NOT_IN_COMMENT;
        }
        break;
      case BLOCK_COMMENT:
        if (c == '*') {
          state = CommentState.BLOCK_COMMENT_OUT_CANDIDATE;
        }
        break;
      case BLOCK_COMMENT_OUT_CANDIDATE:
        if (c == '/') {
          state = CommentState.NOT_IN_COMMENT;
        } else {
          state = CommentState.BLOCK_COMMENT;
        }
        break;
      }
    }

    boolean isInComment() {
      return CommentState.LINE_COMMENT.equals(state)
          || CommentState.BLOCK_COMMENT.equals(state)
          || CommentState.BLOCK_COMMENT_OUT_CANDIDATE.equals(state);
    }

  }

  enum CommentState {
    NOT_IN_COMMENT,
    COMMENT_CANDIDATE,
    LINE_COMMENT,
    BLOCK_COMMENT,
    BLOCK_COMMENT_OUT_CANDIDATE
  }

  static final class ClassNameMachine {

    ClassNameState state = ClassNameState.UNDEFINED;
    int classNameFrom = -1;
    int classNameTo = -1;

    boolean isFound() {
      return classNameFrom != -1 && classNameTo != -1;
    }

    void reset() {
      state = ClassNameState.UNDEFINED;
      classNameFrom = -1;
      classNameTo = -1;
    }

    void nextChar(char c, int i) {
      switch (state) {
      case UNDEFINED:
        if (isUpperCaseAlphabetic(c)) {
          state = ClassNameState.CLASS_NAME;
          classNameFrom = i;
          classNameTo = -1;
        } else {
          classNameFrom = -1;
          classNameTo = -1;
        }
        break;
      case CLASS_NAME:
        if (!isAlphabetic(c) && !isDigit(c) && c != '_') {
          state = ClassNameState.UNDEFINED;
          classNameTo = i;
        }
        break;
      }
    }

  }

  enum ClassNameState {
    UNDEFINED,
    CLASS_NAME
  }

  private static final class ImportCandidate {

    final int importFrom;
    final int importTo;
    final int packageTo;
    String preparedImport;

    private ImportCandidate(int importFrom, int importTo, int packageTo, String preparedImport) {
      this.importFrom = importFrom;
      this.importTo = importTo;
      this.packageTo = packageTo;
      this.preparedImport = preparedImport;
    }

  }

  private static boolean isDigit(char c) {
    return c >= '0' && c <= '9';
  }

  private static boolean isAlphabetic(char c) {
    return isLowerCaseAlphabetic(c) || isUpperCaseAlphabetic(c);
  }

  private static boolean isLowerCaseAlphabetic(char c) {
    return c >= 'a' && c <= 'z';
  }

  private static boolean isUpperCaseAlphabetic(char c) {
    return c >= 'A' && c <= 'Z';
  }

  private static boolean isSeparator(char c) {
    return c == '(' || c == '<' || c == ')' || c == '>' || c == '{' || c == '}' || c == ',' || c == ';';
  }
}
