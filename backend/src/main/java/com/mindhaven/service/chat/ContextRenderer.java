package com.mindhaven.service.chat;

import com.mindhaven.model.knowledge.Citation;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Formats reference data at the model boundary; XML tags are not a security boundary.
 */
@Component
public class ContextRenderer {
    public String summary(String content) {
        return "<conversation_summary>\n" + escape(content) + "\n</conversation_summary>";
    }

    public String currentTurn(String question, List<Citation> documents) {
        if (documents.isEmpty()) return question;
        StringBuilder text = new StringBuilder("<reference_documents>\n");
        for (var d : documents) {
            text.append("  <document id=\"").append(escape(d.id())).append("\" version=\"").append(escape(d.version())).append("\">\n").append("    <title>").append(escape(d.title())).append("</title>\n").append("    <source>").append(escape(d.sourceUrl())).append("</source>\n").append("    <content>").append(escape(d.text())).append("</content>\n").append("  </document>\n");
        }
        return text.append("</reference_documents>\n\n<current_question>\n").append(escape(question)).append("\n</current_question>").toString();
    }

    private String escape(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }
}
