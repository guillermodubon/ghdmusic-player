package io.github.guillermodubon.musicplayer.repository.dao;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;


public interface Dao<T, K> {
    /**
     * Searches for an entity by its primary key.
     */
    Optional<T> findById(K k) throws SQLException;

    /**
     * Retrieves all entities of this type.
     */
    List<T> findAll() throws SQLException;

    /**
     * Inserts a new entity into the database.
     * @return the generated key (e.g., the auto-incremented key)
     */
    void insert(T entity) throws SQLException;

    /**
     * Updates an existing entity.
     */
    void update(T entity) throws SQLException;

    /**
     * Deletes the entity with the given key.
     */
    void delete(K k) throws SQLException;
}

