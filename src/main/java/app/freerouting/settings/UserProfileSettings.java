package app.freerouting.settings;

import com.google.gson.annotations.SerializedName;
import java.io.Serializable;

public class UserProfileSettings implements Serializable {

  @SerializedName("id")
  public String userId = "00000000-0000-0000-0000-000000000000";
  @SerializedName("email")
  public String userEmail = "";
  @SerializedName("allow_telemetry")
  public Boolean isTelemetryAllowed = true;
  @SerializedName("allow_contact")
  public Boolean isContactAllowed = true;
}