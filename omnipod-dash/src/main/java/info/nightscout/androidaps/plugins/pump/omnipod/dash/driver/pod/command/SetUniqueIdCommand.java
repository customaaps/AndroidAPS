package info.nightscout.androidaps.plugins.pump.omnipod.dash.driver.pod.command;

import java.nio.ByteBuffer;
import java.util.Calendar;
import java.util.Date;

public final class SetUniqueIdCommand extends CommandBase {
    private static final int DEFAULT_ADDRESS = -1;
    private static final short LENGTH = 21;
    private static final byte BODY_LENGTH = 19;

    private final int lotNumber;
    private final int podSequenceNumber;
    private final Date initializationTime;

    private SetUniqueIdCommand(int address, short sequenceNumber, boolean multiCommandFlag, int lotNumber, int podSequenceNumber, Date initializationTime) {
        super(CommandType.SET_UNIQUE_ID, address, sequenceNumber, multiCommandFlag);
        this.lotNumber = lotNumber;
        this.podSequenceNumber = podSequenceNumber;
        this.initializationTime = initializationTime;
    }

    @Override public byte[] getEncoded() {
        return appendCrc(ByteBuffer.allocate(LENGTH + HEADER_LENGTH) //
                .put(encodeHeader(DEFAULT_ADDRESS, sequenceNumber, LENGTH, multiCommandFlag)) //
                .put(commandType.getValue()) //
                .put(BODY_LENGTH) //
                .putInt(address) //
                .put((byte) 0x14) // FIXME ??
                .put((byte) 0x04) // FIXME ??
                .put(encodeInitializationTime(initializationTime)) //
                .putInt(lotNumber) //
                .putInt(podSequenceNumber) //
                .array());
    }

    private static byte[] encodeInitializationTime(Date date) {
        Calendar instance = Calendar.getInstance();
        instance.setTime(date);

        return new byte[]{ //
                (byte) (instance.get(Calendar.MONTH) + 1), //
                (byte) instance.get(Calendar.DATE), //
                (byte) (instance.get(Calendar.YEAR) % 100), //
                (byte) instance.get(Calendar.HOUR_OF_DAY), //
                (byte) instance.get(Calendar.MINUTE) //
        };
    }

    @Override public String toString() {
        return "SetUniqueIdCommand{" +
                "lotNumber=" + lotNumber +
                ", podSequenceNumber=" + podSequenceNumber +
                ", initializationTime=" + initializationTime +
                ", commandType=" + commandType +
                ", address=" + address +
                ", sequenceNumber=" + sequenceNumber +
                ", multiCommandFlag=" + multiCommandFlag +
                '}';
    }

    public static final class Builder extends CommandBase.Builder<Builder, SetUniqueIdCommand> {
        private Integer lotNumber;
        private Integer podSequenceNumber;
        private Date initializationTime;

        public Builder setLotNumber(int lotNumber) {
            this.lotNumber = lotNumber;
            return this;
        }

        public Builder setPodSequenceNumber(int podSequenceNumber) {
            this.podSequenceNumber = podSequenceNumber;
            return this;
        }

        public Builder setInitializationTime(Date initializationTime) {
            this.initializationTime = initializationTime;
            return this;
        }

        @Override final SetUniqueIdCommand buildCommand() {
            if (lotNumber == null) {
                throw new IllegalArgumentException("lotNumber can not be null");
            }
            if (podSequenceNumber == null) {
                throw new IllegalArgumentException("podSequenceNumber can not be null");
            }
            if (initializationTime == null) {
                throw new IllegalArgumentException("initializationTime can not be null");
            }
            return new SetUniqueIdCommand(address, sequenceNumber, multiCommandFlag, lotNumber, podSequenceNumber, initializationTime);
        }
    }
}
