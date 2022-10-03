package com.datastax.oss.pulsar.functions.transforms.model.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import javax.annotation.processing.Generated;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"type", "fields"})
@Generated("jsonschema2pojo")
public class Properties__2 {

  @JsonProperty("type")
  private Type__1 type;

  @JsonProperty("fields")
  private Fields fields;

  @JsonProperty("type")
  public Type__1 getType() {
    return type;
  }

  @JsonProperty("type")
  public void setType(Type__1 type) {
    this.type = type;
  }

  @JsonProperty("fields")
  public Fields getFields() {
    return fields;
  }

  @JsonProperty("fields")
  public void setFields(Fields fields) {
    this.fields = fields;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append(Properties__2.class.getName())
        .append('@')
        .append(Integer.toHexString(System.identityHashCode(this)))
        .append('[');
    sb.append("type");
    sb.append('=');
    sb.append(((this.type == null) ? "<null>" : this.type));
    sb.append(',');
    sb.append("fields");
    sb.append('=');
    sb.append(((this.fields == null) ? "<null>" : this.fields));
    sb.append(',');
    if (sb.charAt((sb.length() - 1)) == ',') {
      sb.setCharAt((sb.length() - 1), ']');
    } else {
      sb.append(']');
    }
    return sb.toString();
  }

  @Override
  public int hashCode() {
    int result = 1;
    result = ((result * 31) + ((this.type == null) ? 0 : this.type.hashCode()));
    result = ((result * 31) + ((this.fields == null) ? 0 : this.fields.hashCode()));
    return result;
  }

  @Override
  public boolean equals(Object other) {
    if (other == this) {
      return true;
    }
    if ((other instanceof Properties__2) == false) {
      return false;
    }
    Properties__2 rhs = ((Properties__2) other);
    return (((this.type == rhs.type) || ((this.type != null) && this.type.equals(rhs.type)))
        && ((this.fields == rhs.fields)
            || ((this.fields != null) && this.fields.equals(rhs.fields))));
  }
}
