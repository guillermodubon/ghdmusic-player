package io.github.guillermodubon.musicplayer.controllers.ui.components.cards;

import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.base.BaseCardController;
import io.github.guillermodubon.musicplayer.controllers.ui.components.cards.data.SearchResultMusicCardData;

public class SearchResultMusicCard extends BaseCardController<SearchResultMusicCardData> {
    private static final String HOVER_CLASS = "search-result-card-hover";
    private static final String BODY_HOVER_CLASS = "search-result-card-body-hover";
    private static final Color TITLE_COLOR = Color.WHITE;
    private static final Color SUBTITLE_COLOR = Color.web("#afafaf");

    @FXML private StackPane rootPane;
    @FXML private HBox body;
    @FXML private ImageView coverView;
    @FXML private Label titleLabel;
    @FXML private Label subtitleLabel;

    public void init(SearchResultMusicCardData data) {
        if (data == null) throw new IllegalArgumentException("data == null");
        setModel(data, null);

        setImageOrFallback(
                coverView,
                data.cover(),
                defaultImage("/io/github/guillermodubon/musicplayer/assets/images/byDefaultImages/defaultPlaylist.png")
        );

        titleLabel.setText(safeText(data.title(), "Unknown"));
        enforceTitleStyle(titleLabel);

        String artists = (data.artists() == null || data.artists().isEmpty())
                ? "Unknown"
                : String.join(", ", data.artists());

        subtitleLabel.setText(safeText(data.type(), "Unknown") + " - " + artists);
        enforceSubtitleStyle(subtitleLabel);

        bindCardClick(rootPane, data.id(), data.onPlay());
    }

    private void enforceTitleStyle(Label label) {
        if (label == null) return;
        label.setFont(Font.font("Archivo", FontWeight.BOLD, 15));
        label.setTextFill(TITLE_COLOR);
        label.setStyle(
                "-fx-font-family: 'Archivo';"
                        + "-fx-font-weight: bold;"
                        + "-fx-font-size: 15px;"
                        + "-fx-font-smoothing-type: lcd;"
                        + "-fx-text-fill: #FFFFFF;"
        );
    }

    private void enforceSubtitleStyle(Label label) {
        if (label == null) return;
        label.setFont(Font.font("Inter", FontWeight.NORMAL, 13));
        label.setTextFill(SUBTITLE_COLOR);
        label.setStyle(
                "-fx-font-family: 'Inter';"
                        + "-fx-font-weight: normal;"
                        + "-fx-font-size: 13px;"
                        + "-fx-font-smoothing-type: lcd;"
                        + "-fx-text-fill: #AFAFAF;"
        );
    }

    @FXML
    private void onHover(MouseEvent e) {
        if (rootPane != null && !rootPane.getStyleClass().contains(HOVER_CLASS)) {
            rootPane.getStyleClass().add(HOVER_CLASS);
        }
        if (body != null && !body.getStyleClass().contains(BODY_HOVER_CLASS)) {
            body.getStyleClass().add(BODY_HOVER_CLASS);
        }
    }

    @FXML
    private void onExit(MouseEvent e) {
        if (rootPane != null) {
            rootPane.getStyleClass().remove(HOVER_CLASS);
        }
        if (body != null) {
            body.getStyleClass().remove(BODY_HOVER_CLASS);
        }
    }
}
