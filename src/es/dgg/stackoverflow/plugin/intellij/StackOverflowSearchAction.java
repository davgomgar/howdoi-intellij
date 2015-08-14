package es.dgg.stackoverflow.plugin.intellij;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import es.dgg.howdoi.HowDoI;
import es.dgg.howdoi.QueryStringGenerator;
import es.dgg.howdoi.google.GoogleQueryStringGenerator;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.*;

/**
 * StackOverflowSearchAction - Performs a search in Stack Overflow and pastes the results of the search in the active
 * editor.
 */
public class StackOverflowSearchAction extends AnAction {
    
    final ExecutorService pool = Executors.newFixedThreadPool(3);

    private final Logger logger = LoggerFactory.getLogger(StackOverflowSearchAction.class);

    @Override
    public void update(final AnActionEvent e) {
        final Project project = e.getData(CommonDataKeys.PROJECT);
        final Editor editor = e.getData(CommonDataKeys.EDITOR);
        //Set visibility only in case of existing project and editor
        e.getPresentation().setVisible(isOptionVisibleInContextMenu(project, editor));
    }

    private boolean isOptionVisibleInContextMenu(Project project, Editor editor) {
        return project != null && editor != null && anyCaretHasText(editor.getCaretModel());
    }

    private boolean anyCaretHasText(CaretModel caretModel) {
       return caretModel.getAllCarets().stream().anyMatch(this::caretHasText);
    }

    private boolean caretHasText(Caret caret) {
        boolean isNotEmptyCaret = false;
        caret.selectLineAtCaret();
        String textAtCaret = caret.getSelectedText();
        if (textAtCaret != null) {
            isNotEmptyCaret =  StringUtils.isNotBlank(textAtCaret);
        }
        caret.removeSelection();
        return isNotEmptyCaret;
    }

    public void actionPerformed(AnActionEvent anActionEvent) {
        final Editor editor = anActionEvent.getRequiredData(CommonDataKeys.EDITOR);
        final Project project = anActionEvent.getRequiredData(CommonDataKeys.PROJECT);
        final Document document = editor.getDocument();
        CaretModel caretModel = editor.getCaretModel();
        PsiFile psiFile = anActionEvent.getData(LangDataKeys.PSI_FILE);
        String language = psiFile.getLanguage().getDisplayName();
        SelectionModel selectionModel = editor.getSelectionModel();
        caretModel.runForEachCaret(caret -> runTaskForCaret(caret, selectionModel, document, project, language));
    }

    private void runTaskForCaret(Caret caret, SelectionModel selectionModel, final Document document, Project project,
                                 String language) {
        logger.debug("Start of Task for caret '{}'", caret);
        caret.selectLineAtCaret();
        String searchText = caret.getSelectedText();
        final int start = selectionModel.getSelectionStart();
        final int end = selectionModel.getSelectionEnd();
        try {

            if (StringUtils.isNotBlank(sanitizesInput(searchText))) {
                String contextSensitiveSearch = searchText.concat(" ").concat(language);
                Callable<Optional<String>> callable = () -> fetchStackOverflowResult(contextSensitiveSearch);
                Future<Optional<String>> submit = pool.submit(callable);
                Optional<String> retrievedValue = submit.get();
                Runnable runnable = () -> document.replaceString(start, end, retrievedValue.get().concat("\n"));
                WriteCommandAction.runWriteCommandAction(project, runnable);
                selectionModel.removeSelection();
            }
        } catch (InterruptedException  | ExecutionException e) {
            logger.error("An exception has been thrown: ", e);
        }
        logger.debug("End of task for caret '{}' ", caret);
    }

    private Optional<String> fetchStackOverflowResult(final String text) {
        String sanitizedText = sanitizesInput(text);
        QueryStringGenerator generator = new GoogleQueryStringGenerator();
        try {
            return new HowDoI(sanitizedText, generator).getAnswer();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private String sanitizesInput(String text) {
        return (text) != null ? text.replaceAll("\\?", "").replaceAll("\\n", "") : StringUtils.EMPTY;
    }
}
