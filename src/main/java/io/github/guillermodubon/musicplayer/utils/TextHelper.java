package io.github.guillermodubon.musicplayer.utils;

public class TextHelper {

    public String summarizeAndCleanTextByPeriods(String text, int periods) {

        String summarizedText;

        int periodCount = 0;
        int cutIndex = -1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '.') {
                periodCount++;
                if (periodCount == periods) {
                    cutIndex = i + 1;
                    break;
                }
            }
        }
        if (cutIndex != -1 && cutIndex < text.length()) {
            summarizedText = text.substring(0, cutIndex).trim();
        } else {
            summarizedText = text.trim();
        }

        return summarizedText;

    }
}
