package io.github.guillermodubon.musicplayer.services.downloads.services;

import io.github.guillermodubon.musicplayer.services.downloads.context.DownloadTaskContext;
import io.github.guillermodubon.musicplayer.services.downloads.logging.DownloadLog;
import io.github.guillermodubon.musicplayer.models.DeezerApiMetaData;
import io.github.guillermodubon.musicplayer.services.startup.StartUpService;

import java.io.File;
import java.util.concurrent.CompletableFuture;

public class DownloadPostProcessorService {

    /**
     * Método heredado sin contexto de descarga.
     *
     * Conservado para compatibilidad con llamadas anteriores.
     */
    public void process(
            DeezerApiMetaData meta,
            File finalFile
    ) {
        processAsync(null, meta, finalFile);
    }

    /**
     * Procesa la descarga conservando su contexto original.
     */
    public void process(
            DownloadTaskContext taskContext,
            DeezerApiMetaData meta,
            File finalFile
    ) {
        processAsync(taskContext, meta, finalFile);
    }

    /**
     * Método heredado sin contexto de descarga.
     *
     * Conservado para compatibilidad con llamadas anteriores.
     */
    public CompletableFuture<Void> processAsync(
            DeezerApiMetaData meta,
            File finalFile
    ) {
        return processAsync(null, meta, finalFile);
    }

    /**
     * Ejecuta las dos fases de integración:
     *
     * 1. Persistencia e hidratación de la canción.
     * 2. Publicación final a la UI y al flujo de reproducción.
     *
     * El DownloadTaskContext se conserva hasta la publicación final para poder
     * identificar el Song original y la colección desde donde se descargó.
     */
    public CompletableFuture<Void> processAsync(
            DownloadTaskContext taskContext,
            DeezerApiMetaData meta,
            File finalFile
    ) {
        return prepareAsync(meta, finalFile)
                .thenCompose(ignored ->
                        publishFinalAsync(
                                taskContext,
                                meta,
                                finalFile
                        )
                );
    }

    /**
     * Persiste e hidrata todos los datos necesarios para la reproducción,
     * pero todavía no publica el elemento reproducible en JavaFX.
     */
    public CompletableFuture<Void> prepareAsync(
            DeezerApiMetaData meta,
            File finalFile
    ) {
        if (finalFile == null) {
            return CompletableFuture.completedFuture(null);
        }

        DownloadLog.info(
                "DownloadPostProcessor",
                "Preparing durable library integration for "
                        + DownloadLog.pathOf(finalFile)
        );

        try {
            StartUpService service = StartUpService.getInstance();

            if (service == null) {
                DownloadLog.warn(
                        "DownloadPostProcessor",
                        "StartUpService is unavailable; library was not updated"
                );

                return CompletableFuture.completedFuture(null);
            }

            CompletableFuture<Void> completion =
                    service.prepareDownloadedSongAsync(
                            meta,
                            finalFile
                    );

            DownloadLog.info(
                    "DownloadPostProcessor",
                    "Durable library integration scheduled"
            );

            return completion;

        } catch (Exception error) {
            DownloadLog.error(
                    "DownloadPostProcessor",
                    "Could not update library after download",
                    error
            );

            return CompletableFuture.failedFuture(error);
        }
    }

    /**
     * Método heredado sin contexto de descarga.
     *
     * Conservado para llamadas antiguas. Las descargas creadas desde canciones
     * conocidas deben usar la sobrecarga que recibe DownloadTaskContext.
     */
    public CompletableFuture<Void> publishFinalAsync(
            DeezerApiMetaData meta,
            File finalFile
    ) {
        return publishFinalAsync(
                null,
                meta,
                finalFile
        );
    }

    /**
     * Publica la canción después de completar la fase durable.
     *
     * El contexto permite mantener:
     *
     * - La canción remota original.
     * - El ID del álbum o playlist de origen.
     * - El tipo de colección.
     * - La relación con el flujo de reproducción activo.
     */
    public CompletableFuture<Void> publishFinalAsync(
            DownloadTaskContext taskContext,
            DeezerApiMetaData meta,
            File finalFile
    ) {
        if (finalFile == null) {
            return CompletableFuture.completedFuture(null);
        }

        try {
            StartUpService service =
                    StartUpService.getInstance();

            if (service == null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException(
                                "StartUpService is unavailable during "
                                        + "final download publication"
                        )
                );
            }

            return service.publishFullyIntegratedDownloadAsync(
                    taskContext,
                    meta,
                    finalFile
            );

        } catch (Exception error) {
            DownloadLog.error(
                    "DownloadPostProcessor",
                    "Could not publish integrated download",
                    error
            );

            return CompletableFuture.failedFuture(error);
        }
    }
}
