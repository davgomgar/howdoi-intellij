package es.dgg.stackoverflow.plugin.intellij;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.project.Project;
import es.dgg.howdoi.HowDoI;
import es.dgg.howdoi.google.GoogleQueryStringGenerator;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.*;

/**
 * Created by david on 6/8/15.
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
        boolean result = false;
        for (Caret caret : caretModel.getAllCarets()) {
            caret.selectLineAtCaret();
            String text = caret.getSelectedText().trim();
            caret.removeSelection();
            if (StringUtils.isNotEmpty(text)) {
                result = true;
                break;
            }
        }
        return result;
    }

    public void actionPerformed(AnActionEvent anActionEvent) {
        //Get all the required data from data keys
        final Editor editor = anActionEvent.getRequiredData(CommonDataKeys.EDITOR);
        final Project project = anActionEvent.getRequiredData(CommonDataKeys.PROJECT);
        //Access document, caret, and selection
        final Document document = editor.getDocument();
        CaretModel caretModel = editor.getCaretModel();
        SelectionModel selectionModel = editor.getSelectionModel();
        caretModel.runForEachCaret(caret -> runTaskForCaret(caret, selectionModel, document, project));
    }

    private void runTaskForCaret(Caret caret, SelectionModel selectionModel, final Document document, Project project) {
        logger.debug("Start of Task for caret '{}'", caret);
        caret.selectLineAtCaret();
        String textAtCaret = caret.getSelectedText();
        final int start = selectionModel.getSelectionStart();
        final int end = selectionModel.getSelectionEnd();
        try {
            if (StringUtils.isNotEmpty(textAtCaret)) {
                Callable<Optional<String>> callable = () -> fetchStackOverflowResult(textAtCaret);
                Future<Optional<String>> submit = pool.submit(callable);
                Optional<String> retrievedValue = submit.get();
                Runnable runnable = () -> document.replaceString(start, end, retrievedValue.get());
                WriteCommandAction.runWriteCommandAction(project, runnable);
                selectionModel.removeSelection();
            }
        } catch (InterruptedException  | ExecutionException e) {
            logger.error("An exception has been thrown: ", e);
        }
        logger.debug("End of task for caret '{}' ", caret);
    }

    private Optional<String> fetchStackOverflowResult(final String text) {
        String sanitizedText = text.replaceAll("\\?", "");
        GoogleQueryStringGenerator generator = new GoogleQueryStringGenerator();
        try {
            return new HowDoI(sanitizedText, generator).getAnswer();
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
