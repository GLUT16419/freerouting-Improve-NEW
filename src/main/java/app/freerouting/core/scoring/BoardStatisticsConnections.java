package app.freerouting.core.scoring;

import com.google.gson.annotations.SerializedName;
import java.io.Serializable;

public class BoardStatisticsConnections implements Serializable {

  @SerializedName("maximum_count")
  public Integer maximumCount;
  @SerializedName("incomplete_count")
  public Integer incompleteCount;
  @SerializedName("signal_incomplete_count")
  public Integer signalIncompleteCount;
  @SerializedName("power_incomplete_count")
  public Integer powerIncompleteCount;
  @SerializedName("ground_incomplete_count")
  public Integer groundIncompleteCount;
}