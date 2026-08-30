package org.tornotron.echno_backend.indentItem;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface IndentItemRepository extends JpaRepository<IndentItem, Long> {

    Optional<IndentItem> findByIdAndOrganization_Id(Long id, Long organizationId);

    List<IndentItem> findByIndentId(Long indentId);

    List<IndentItem> findByMaterialId(Long materialId);

    List<IndentItem> findByConvertedToPurchaseOrder(Boolean converted);

    boolean existsByIdAndOrganization_Id(Long id, Long organizationId);

    void deleteByIdAndOrganization_Id(Long id, Long organizationId);

    /**
     * Counts the item lines of many indents in one grouped read.
     *
     * <p>The count is all a list of indents needs; reaching it through the mapped {@code items}
     * collection loads every line, and every line carries a material, so a page of indents pays
     * for the material graph and its stock aggregate to render a column of numbers.
     *
     * <p>An indent with no lines produces no group, so the caller supplies the zero. Pass a
     * non-empty collection: {@code IN ()} is not valid SQL.
     *
     * @param indentIds The indents to count, non-empty.
     * @return One row per indent that has at least one line.
     */
    @Query("""
            SELECT new org.tornotron.echno_backend.indentItem.IndentItemCount(
                       item.indent.id,
                       COUNT(item))
            FROM IndentItem item
            WHERE item.indent.id IN :indentIds
            GROUP BY item.indent.id
            """)
    List<IndentItemCount> countItemsByIndentIds(@Param("indentIds") Collection<Long> indentIds);
}
