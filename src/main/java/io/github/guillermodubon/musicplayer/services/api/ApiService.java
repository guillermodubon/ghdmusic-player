package io.github.guillermodubon.musicplayer.services.api;

import java.util.List;

public interface ApiService<T> {

    List<T> getApiObjectsList(List<String> keys);

}
