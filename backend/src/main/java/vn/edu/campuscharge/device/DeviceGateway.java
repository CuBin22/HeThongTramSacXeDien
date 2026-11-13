package vn.edu.campuscharge.device;

import java.util.UUID;

/** Delivery acceptance only; session state changes after a matching device acknowledgement. */
public interface DeviceGateway {
    record AcceptedCommand(UUID commandId) {}
    AcceptedCommand start(UUID commandId,UUID portId,int initialBatteryPercent);
    AcceptedCommand stop(UUID commandId,UUID portId,String reason);
}
