package ac.grim.grimac.checks.impl.movement;

import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketCheck;
import ac.grim.grimac.player.GrimPlayer;
import io.github.retrooper.packetevents.event.impl.PacketPlayReceiveEvent;
import io.github.retrooper.packetevents.packettype.PacketType;

@CheckData(name = "Timer (Experimental)", configName = "TimerA", flagCooldown = 1000, maxBuffer = 5)
public class TimerCheck extends PacketCheck {
    public int exempt = 200; // Exempt for 10 seconds on login
    GrimPlayer player;

    long timerBalanceRealTime = 0;

    // Default value is real time minus max keep-alive time
    long knownPlayerClockTime = (long) (System.nanoTime() - 6e10);
    long lastMovementPlayerClock = (long) (System.nanoTime() - 6e10);

    boolean hasGottenMovementAfterTransaction = false;

    // Proof for this timer check
    // https://i.imgur.com/Hk2Wb6c.png
    //
    // The largest gap will always be the transaction ping (server -> client -> server)
    // Proof lies that client -> server ping will always be lower
    //
    // The largest gap is the floor for movements
    // If the smaller gap surpasses the larger gap, the player is cheating
    //
    // This usually flags 1.01 on low ping extremely quickly
    // Higher ping/low fps scales proportionately, and will flag less quickly but will still always flag 1.01
    // Players standing still will reset this amount of time
    //
    // This is better than traditional timer checks because ping fluctuations will never affect this check
    // As we are tying this check to the player's ping, rather than real time.
    //
    // Tested 10/20/30 fps and f3 + t spamming for lag spikes at 0 ping localhost/200 ping clumsy, no falses
    // Also didn't false when going from 0 -> 2000 ms ping, and 2000 ms -> 0 ms ping
    // it's a very nice check, in my opinion.  I guess I will find out if netty lag can false it

    // You might notice that we deviate a bit from this to handle lag
    // We take the FIRST transaction after each movement, to avoid issues with this packet order at low FPS:
    // TRANSACTION TRANSACTION TRANSACTION MOVEMENT MOVEMENT MOVEMENT
    // TRANSACTION TRANSACTION TRANSACTION MOVEMENT MOVEMENT MOVEMENT
    //
    // We then take the last transaction before this to increase stability with these lag spikes and
    // to guarantee that we are at least 50 ms back before adding the time
    public TimerCheck(GrimPlayer player) {
        super(player);
        this.player = player;
    }

    public void onPacketReceive(final PacketPlayReceiveEvent event) {
        if (hasGottenMovementAfterTransaction && checkForTransaction(event.getPacketId())) {
            knownPlayerClockTime = lastMovementPlayerClock;
            lastMovementPlayerClock = player.getPlayerClockAtLeast();
            hasGottenMovementAfterTransaction = false;
        }

        if (checkReturnPacketType(event.getPacketId())) return;

        player.movementPackets++;
        hasGottenMovementAfterTransaction = true;

        // Teleporting sends its own packet (We could handle this, but it's not worth the complexity)
        if (exempt-- > 0) {
            return;
        }
        exempt = 0;

        timerBalanceRealTime += 50e6;

        if (timerBalanceRealTime > System.nanoTime()) {
            increaseViolations();
            alert("", getCheckName(), formatViolations());

            // Reset the violation by 1 movement
            timerBalanceRealTime -= 50e6;
        } else {
            // Decrease buffer as to target 1.005 timer - 0.005
            reward();
        }

        timerBalanceRealTime = Math.max(timerBalanceRealTime, lastMovementPlayerClock);
    }

    public boolean checkForTransaction(byte packetType) {
        return packetType == PacketType.Play.Client.PONG ||
                packetType == PacketType.Play.Client.TRANSACTION;
    }

    public boolean checkReturnPacketType(byte packetType) {
        // If not flying, or this was a teleport, or this was a duplicate 1.17 mojang stupidity packet
        return !PacketType.Play.Client.Util.isInstanceOfFlying(packetType) ||
                player.packetStateData.lastPacketWasTeleport || player.packetStateData.lastPacketWasOnePointSeventeenDuplicate;
    }
}
