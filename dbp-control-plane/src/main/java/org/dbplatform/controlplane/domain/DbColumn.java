package org.dbplatform.controlplane.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** API resource name: {@code Column}. */
@Entity
@Table(name = "db_column")
@JsonIgnoreProperties(ignoreUnknown = true)
public class DbColumn {
    @Id @Column(length = 36) private String id;
    @Column(nullable = false, length = 36) private String tableId;
    @Column(nullable = false) private String name;
    @Column(name = "ordinal_position", nullable = false) private int position;
    private String dataType;
    @Column(name = "char_length") private Integer length;
    @Column(name = "num_precision") private Integer precision;
    @Column(name = "num_scale") private Integer scale;
    @Column(nullable = false) private boolean nullable = true;
    private String defaultValue;
    private String comment;
    @Enumerated(EnumType.STRING) @Column(length = 20) private Enums.Classification classification;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTableId() { return tableId; }
    public void setTableId(String tableId) { this.tableId = tableId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
    public String getDataType() { return dataType; }
    public void setDataType(String dataType) { this.dataType = dataType; }
    public Integer getLength() { return length; }
    public void setLength(Integer length) { this.length = length; }
    public Integer getPrecision() { return precision; }
    public void setPrecision(Integer precision) { this.precision = precision; }
    public Integer getScale() { return scale; }
    public void setScale(Integer scale) { this.scale = scale; }
    public boolean isNullable() { return nullable; }
    public void setNullable(boolean nullable) { this.nullable = nullable; }
    public String getDefaultValue() { return defaultValue; }
    public void setDefaultValue(String defaultValue) { this.defaultValue = defaultValue; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
    public Enums.Classification getClassification() { return classification; }
    public void setClassification(Enums.Classification classification) { this.classification = classification; }
}
