package com.datastax.oss.pulsar.functions.transforms.model.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import javax.annotation.processing.Generated;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"minLength"})
@Generated("jsonschema2pojo")
public class Message__1 {

  @JsonProperty("minLength")
  private String minLength;

  @JsonProperty("minLength")
  public String getMinLength() {
    return minLength;
  }

  @JsonProperty("minLength")
  public void setMinLength(String minLength) {
    this.minLength = minLength;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append(Message__1.class.getName())
        .append('@')
        .append(Integer.toHexString(System.identityHashCode(this)))
        .append('[');
    sb.append("minLength");
    sb.append('=');
    sb.append(((this.minLength == null) ? "<null>" : this.minLength));
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
    result = ((result * 31) + ((this.minLength == null) ? 0 : this.minLength.hashCode()));
    return result;
  }

  @Override
  public boolean equals(Object other) {
    if (other == this) {
      return true;
    }
    if ((other instanceof Message__1) == false) {
      return false;
    }
    Message__1 rhs = ((Message__1) other);
    return ((this.minLength == rhs.minLength)
        || ((this.minLength != null) && this.minLength.equals(rhs.minLength)));
  }
}
