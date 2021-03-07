package org.firstinspires.ftc.teamcode.RobotStuff.diffyswerve;

import com.acmerobotics.dashboard.FtcDashboard;
import com.acmerobotics.dashboard.telemetry.TelemetryPacket;
import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorSimple;


import org.firstinspires.ftc.robotcore.external.Telemetry;
import org.firstinspires.ftc.teamcode.Misc.DataLogger;
import org.firstinspires.ftc.teamcode.Misc.PID;

import Dashboard.RobotConstants;


public class DriveModule {
    Robot robot;
    public final ModuleSide moduleSide;
    public final Vector2d positionVector;
    DataLogger dataLogger;
    boolean debuggingMode;

    public DcMotor motor1; //top motor
    public DcMotor motor2; //bottom motor
    public DcMotor encoderOrientation;


    //Through Bore encoders
    // They are in motor 1 and motor 4 slots so set motors to run without using encoders
    private double lastThrougBoreEncoder;

  // motor2 encoder will be used to find distance travelled
    private double lastMotor2Encoder;
    private double distanceTraveled;

    private double lastorientEncoder;
    double lastM1Encoder;
    double lastM2Encoder;




    //used for logic that allows robot to rotate modules as little as possible
    //todo attempt to make the code more elegant
    public boolean takingShortestPath = false;
    public boolean reversed = false;


    // Used constants used only for drive module
    public static final double TICKS_PER_MODULE_REV = 8192;
    public final double DEGREES_PER_TICK = 360/TICKS_PER_MODULE_REV;
    public static final double TICKS_PER_WHEEL_REV = 1 * TICKS_PER_MODULE_REV * 18/60; //ticks per WHEEL revolution


    public static final double CM_WHEEL_DIAMETER = 3 * 2.54;
    public static final double CM_PER_WHEEL_REV = CM_WHEEL_DIAMETER * Math.PI;
    public final double CM_PER_TICK = CM_PER_WHEEL_REV/TICKS_PER_WHEEL_REV;


    public final double MAX_MOTOR_POWER = 1;


    public static final double TICKS_PER_CM = (TICKS_PER_MODULE_REV) / (CM_WHEEL_DIAMETER * Math.PI);


    public final double TICKS_PER_MODULE_REV_ORIENT = 8192; //20 * (double)(60)/14 * (double)(48)/15 * (double)(82)/22; //ticks per MODULE revolution
    public final double DEGREES_PER_TICK_ORIENT = 360/TICKS_PER_MODULE_REV_ORIENT;


    //used for scaling pivot component (see getPivotComponent() method)
    public final double ANGLE_OF_MAX_MODULE_ROTATION_POWER = 60;

    //if module is within this number of degrees from its target orientation, no pivot power will be applied
    public final double ALLOWED_MODULE_ORIENTATION_ERROR = 10;

    //TODO: tune this variable (see commented out section in TeleOp)
    //was 1.7
    //what
    public double ROT_ADVANTAGE = 1.7; //max rotation power divided by max translation power (scaling factor)

    // Unit vectors represent rot power v translation power coordinate system
    public Vector2d MOTOR_1_VECTOR = new Vector2d(1 / Math.sqrt(2), 1 / Math.sqrt(2)); //unit vector (MAY BE SWITCHED with below)
    public Vector2d MOTOR_2_VECTOR = new Vector2d(-1 / Math.sqrt(2), 1 / Math.sqrt(2)); //unit vector


    FtcDashboard dashboard = FtcDashboard.getInstance();
    TelemetryPacket packet = new TelemetryPacket();

    //PID values need to be tuned
    public PID StraightPID = new PID(RobotConstants.Straight_KP, RobotConstants.Straight_KI, RobotConstants.Straight_KD);
    public PID turnToAnglePIDTest = new PID(RobotConstants.GYRO_TURN_KP, RobotConstants.GYRO_TURN_KI, RobotConstants.GYRO_TURN_KD);
    public PID turnToAnglePID = new PID(0.03, 0, 0.003);

    public enum RotateModuleMode {
        DO_NOT_ROTATE_MODULES, ROTATE_MODULES
    }

    public RotateModuleMode rotateModuleMode = RotateModuleMode.ROTATE_MODULES;

    public double positionChange;

    public boolean isRobotCentric = false;

    public DriveModule(Robot robot, ModuleSide moduleSide, boolean debuggingMode) {
        this.robot = robot;
        this.moduleSide = moduleSide;
        this.debuggingMode = debuggingMode;

        if (moduleSide == ModuleSide.RIGHT) {
            motor1 = robot.hardwareMap.dcMotor.get("rightTopMotor");
            motor2 = robot.hardwareMap.dcMotor.get("rightBottomMotor");
            motor1.setDirection(DcMotorSimple.Direction.REVERSE);
            positionVector = new Vector2d((double) 13 / 2, 0); //points from robot center to right module
        } else {
            motor1 = robot.hardwareMap.dcMotor.get("leftTopMotor");
            motor2 = robot.hardwareMap.dcMotor.get("leftBottomMotor");
            motor1.setDirection(DcMotorSimple.Direction.REVERSE);
            positionVector = new Vector2d((double) -13 / 2, 0); //points from robot center to left module
        }
        lastThrougBoreEncoder = 0;



        //set run mode to NOT use encoders for  PID regulation
        motor1.setMode(DcMotor.RunMode.RUN_WITHOUT_ENCODER);
        motor2.setMode(DcMotor.RunMode.RUN_WITHOUT_ENCODER);

        //motors will brake when zero power is applied (rather than coast)
        motor1.setZeroPowerBehavior(DcMotor.ZeroPowerBehavior.BRAKE);
        motor2.setZeroPowerBehavior(DcMotor.ZeroPowerBehavior.BRAKE);

        lastorientEncoder = 0;
        lastM1Encoder = 0;
        lastM2Encoder = 0;


        if (debuggingMode) {
            dataLogger = new DataLogger(moduleSide + "ModuleLog");
            dataLogger.addField("Trans Vector FC X");
            dataLogger.addField("Trans Vector FC Y");
            dataLogger.addField("Rot Vector X");
            dataLogger.addField("Rot Vector Y");
            dataLogger.addField("Target Vector X");
            dataLogger.addField("Target Vector Y");
            dataLogger.addField("Module Orientation");
            dataLogger.addField("Reversed");
            dataLogger.addField("Power Vector X (TRANS)");
            dataLogger.addField("Power Vector Y (ROT)");
            dataLogger.addField("Motor 1 Power");
            dataLogger.addField("Motor 2 Power");
            dataLogger.addField("Motor 1 Encoder");
            dataLogger.addField("Motor 2 Encoder");
            dataLogger.newLine();
        }
    }

    //defaults to false for debugging mode (optional parameter)
    public DriveModule(Robot robot, ModuleSide moduleSide) {
        this(robot, moduleSide, false);
    }

    //Flips between robot and field centric
    public void setDrivingStyle(boolean toRobotCentric) {
        isRobotCentric = toRobotCentric;
    }


    //this method updates the target vector for the module based on input from auto/teleop program
    public void updateTarget(Vector2d transVec, double rotMag) { //translation vector and rotation magnitude


        Vector2d transVecFC = transVec.rotateBy(robot.getRobotHeading().getAngle(Angle.AngleType.ZERO_TO_360_HEADING), Angle.Direction.COUNTER_CLOCKWISE);

        //vector needed to rotate robot at the desired magnitude
        //based on positionVector of module (see definition for more info)
        Vector2d rotVec = positionVector.normalize(rotMag).rotateBy(90, Angle.Direction.COUNTER_CLOCKWISE); //theoretically this should be rotated 90, not sure sure it doesn't need to be

        //combine desired robot translation and robot rotation to get goal vector for the module
        Vector2d targetVector = transVecFC.add(rotVec);

        //allows modules to reverse power instead of rotating 180 degrees
        //example: useful when going from driving forwards to driving backwards
        int directionMultiplier = -1; //was positive 1
        if (reversed) { //reverse direction of translation because module is reversed
            targetVector = targetVector.reflect();
            directionMultiplier = 1;
        }

        if (debuggingMode) {
            dataLogger.addField(transVecFC.getX());
            dataLogger.addField(transVecFC.getY());
            dataLogger.addField(rotVec.getX());
            dataLogger.addField(rotVec.getY());
            dataLogger.addField(targetVector.getX());
            dataLogger.addField(targetVector.getY());
            dataLogger.addField(getCurrentOrientation().getAngle());
            dataLogger.addField(reversed);
        }

        //calls method that will apply motor powers necessary to reach target vector in the best way possible, based on current position
        goToTarget(targetVector, directionMultiplier);

        if (debuggingMode) {
            robot.telemetry.addData(moduleSide + " REVERSED: ", reversed);
            robot.telemetry.addData(moduleSide + " Trans Vec FC: ", transVecFC);
            robot.telemetry.addData(moduleSide + " Rot Vec: ", rotVec);
        }
    }
    //used in place of updateTarget() when the "absolute heading mode" is being used in TeleOp
    //"absolute heading mode" means the angle of the rotation joystick is used to determine the desired angle of the robot
    public void updateTargetAbsRotation (Vector2d transVec, Angle targetHeading, double scaleFactor) { //translation vector and rotation magnitude
        Angle robotHeading = robot.getRobotHeading(); //todo: this changed
        double rotMag = getRotMag(targetHeading, robotHeading) * scaleFactor;
        updateTarget(transVec, rotMag);
    }
    public double getRotMag(Angle targetHeading, Angle robotHeading) {
        robot.telemetry.addData("Difference between joystick and robot", targetHeading.getDifference(robotHeading));
        robot.telemetry.addData("Direction from robot to joystick", robotHeading.directionTo(targetHeading));

        double unsignedDifference = RobotUtil.scaleVal(targetHeading.getDifference(robotHeading),15, 60, .3, 1);
        //todo: check if below line is causing problem
        if (robotHeading.directionTo(targetHeading) == Angle.Direction.CLOCKWISE) {
            return unsignedDifference * -1;
        } else {
            return unsignedDifference;
        }
    }

    //sets motor powers for robot to best approach given target vector
    public void goToTarget (Vector2d targetVector, int directionMultiplier) {
        //how much the module needs to translate (and in which direction)
        double moveComponent = targetVector.getMagnitude() * directionMultiplier;

        //how much the module needs to pivot (change its orientation)
        double pivotComponent;
        if (targetVector.getMagnitude() != 0) {
            pivotComponent = getPivotComponent(targetVector, getCurrentOrientation());
        } else {
            //if target vector is zero (joystick is within deadband) don't pivot modules
            pivotComponent = 0;
        }

        //vector in an (invented) coordinate system that represents desired (relative) module translation and module rotation
        Vector2d powerVector = new Vector2d(moveComponent, pivotComponent); //order very important here

        if (debuggingMode) {
            dataLogger.addField(powerVector.getX());
            dataLogger.addField(powerVector.getY());
        }

        setMotorPowers(powerVector);

        if (debuggingMode) {
            robot.telemetry.addData(moduleSide + " Target Vector Angle: ", targetVector.getAngleDouble(Angle.AngleType.ZERO_TO_360_HEADING));
            robot.telemetry.addData(moduleSide + " Power Vector: ", powerVector);
            robot.telemetry.addData(moduleSide + " Current orientation: ", getCurrentOrientation().getAngle());
        }

        if (debuggingMode) {
            dataLogger.addField(robot.bulkData1.getMotorCurrentPosition(motor1));
            dataLogger.addField(robot.bulkData1.getMotorCurrentPosition(motor2));
            dataLogger.newLine();
        }
    }
    //returns a scalar corresponding to how much power the module needs to apply to rotating
    //this is necessary because of the differential nature of a diff swerve drive
    public double getPivotComponent (Vector2d targetVector, Angle currentAngle) {
        Angle targetAngle = targetVector.getAngle();
        double angleDiff = targetAngle.getDifference(currentAngle); //number from 0 to 180 (always positive)

        //allows module to rotate to the opposite position of (180 degrees away from) its target
        //if this is the fastest path, we need to indicate that the direction of translation should be reversed
        if (Math.abs(angleDiff) > 110) { //was 90
            if (!takingShortestPath) {
                reversed = !reversed; //reverse translation direction bc module is newly reversed
            }
            takingShortestPath = true;
        } else {
            takingShortestPath = false;
        }

        if (debuggingMode) robot.telemetry.addData(moduleSide + " Angle diff (abs. value): ", angleDiff);
        Angle.Direction direction = currentAngle.directionTo(targetAngle);

        //CCW is negative for heading system
        if (angleDiff < ALLOWED_MODULE_ORIENTATION_ERROR) {
            //don't rotate module if it's currently within x degrees of its target orientation
            //avoids constant twitching of modules
            return 0;
        } else if (angleDiff > ANGLE_OF_MAX_MODULE_ROTATION_POWER) {
            //rotation power is maxed out if the difference is more than this angle
            if (direction == Angle.Direction.CLOCKWISE) return ROT_ADVANTAGE;
            else return -1 * ROT_ADVANTAGE;
        } else {
            //scale module rotation power based on set constants
            if (direction == Angle.Direction.CLOCKWISE) return angleDiff / ANGLE_OF_MAX_MODULE_ROTATION_POWER * ROT_ADVANTAGE;
            else return -1 * angleDiff / ANGLE_OF_MAX_MODULE_ROTATION_POWER * ROT_ADVANTAGE;
        }
    }
    //takes vector in power vector coordinate system
    // ^(x component is relative translation power and y component is relative MODULE rotation power)
    //calculates motor powers that will result in the desired ratio of module translation and module rotation
    //sets motors to appropriate powers
    public void setMotorPowers (Vector2d powerVector) {

        //this is one way to convert desired ratio of module translation and module rotation to motor powers
        //vectors are not strictly necessary for this, but made it easier to visualize
        //more documentation on this visualization method coming soon
        Vector2d motor1Unscaled = powerVector.projection(MOTOR_1_VECTOR);
        Vector2d motor2Unscaled = powerVector.projection(MOTOR_2_VECTOR);

        //makes sure no vector magnitudes exceed the maximum motor power
        Vector2d[] motorPowersScaled = Vector2d.batchNormalize(MAX_MOTOR_POWER, motor1Unscaled, motor2Unscaled);
        double motor1power = motorPowersScaled[0].getMagnitude();
        double motor2power = motorPowersScaled[1].getMagnitude();

        //this is to add sign to magnitude, which returns an absolute value
        if (motorPowersScaled[0].getAngleDouble(Angle.AngleType.NEG_180_TO_180_CARTESIAN) != MOTOR_1_VECTOR.getAngleDouble(Angle.AngleType.NEG_180_TO_180_CARTESIAN)) {
            motor1power *= -1;
        }
        if (motorPowersScaled[1].getAngleDouble(Angle.AngleType.NEG_180_TO_180_CARTESIAN) != MOTOR_2_VECTOR.getAngleDouble(Angle.AngleType.NEG_180_TO_180_CARTESIAN)) {
            motor2power *= -1;
        }

        if (debuggingMode) {
            robot.telemetry.addData(moduleSide + " Motor 1 Power: ", motor1power);
            robot.telemetry.addData(moduleSide + " Motor 2 Power: ", motor2power);
        }
        motor1.setPower(motor1power);
        motor2.setPower(motor2power);

        if (debuggingMode) {
            dataLogger.addField(motor1power);
            dataLogger.addField(motor2power);
        }
    }
    //for pure module rotation (usually used for precise driving in auto)
    public void rotateModule (Vector2d direction, boolean fieldCentric) {
        //converts robot heading to the angle type used by Vector2d class
        Angle convertedRobotHeading = robot.getRobotHeading().convertAngle(Angle.AngleType.NEG_180_TO_180_CARTESIAN);

        //pass 0 as moveComponent
        //todo: check if fixes broke this
        Vector2d directionFC = direction.rotateTo(robot.getRobotHeading()); //was converted robot heading

        //ADDED
        if (reversed) { //reverse direction of translation because module is reversed
            directionFC = directionFC.reflect();
            direction = direction.reflect();
        }

        Vector2d powerVector;
        if (fieldCentric) {
            powerVector = new Vector2d(0, getPivotComponent(directionFC, getCurrentOrientation())); //order important here
        }
        else {
            powerVector = new Vector2d(0, getPivotComponent(direction, getCurrentOrientation())); //order important here
        }
        setMotorPowers(powerVector);

        if(debuggingMode) {
            robot.telemetry.addData(moduleSide + " Power Vector: ", powerVector);
            robot.telemetry.addData(moduleSide + " Current orientation: ", getCurrentOrientation().getAngle());
        }
    }

    //does not need to be called at the start of every program
    //separate OpMode called ResetEncoders calls this method
    public void resetEncoders () {
        motor1.setMode(DcMotor.RunMode.STOP_AND_RESET_ENCODER);
        motor2.setMode(DcMotor.RunMode.STOP_AND_RESET_ENCODER);
        encoderOrientation.setMode(DcMotor.RunMode.STOP_AND_RESET_ENCODER);
        motor1.setMode(DcMotor.RunMode.RUN_USING_ENCODER);
        motor2.setMode(DcMotor.RunMode.RUN_USING_ENCODER);
        encoderOrientation.setMode(DcMotor.RunMode.RUN_USING_ENCODER);
    }
    //TRACKING METHODS
    //used for straight line distance tracking

    public void updateTracking () {
        //important to set these to a variable so getCurrentPosition() is not called multiple times in single cycle
        double currentMotor2Encoder = motor2.getCurrentPosition();

        double motor2Change = currentMotor2Encoder - lastMotor2Encoder;
        //if module is reversed, subtract distance traveled instead of adding
        //module is driving in the opposite direction that the encoders "think" it is
        if (reversed) {
            distanceTraveled -= (-motor2Change)/2.0 * CM_PER_TICK;
        } else {
            distanceTraveled += (-motor2Change)/2.0 * CM_PER_TICK;
        }
        lastMotor2Encoder = currentMotor2Encoder;

        robot.telemetry.addData(moduleSide + "Motor 2 Encoder", motor2.getCurrentPosition());
        robot.telemetry.update();
    }

    //returns module orientation relative to ROBOT (not field) in degrees and NEG_180_TO_180_HEADING type(fix this for new encoder Through bore
    public Angle getCurrentOrientation() {
        //returns module orientation relative to ROBOT (not field) in degrees and NEG_180_TO_180_HEADING type


            double rawAngleORIENT = (double)(robot.bulkData2.getMotorCurrentPosition(encoderOrientation)) * DEGREES_PER_TICK_ORIENT;
            Angle rawAngleORIENTAngle = new Angle(rawAngleORIENT, Angle.AngleType.ZERO_TO_360_HEADING);


            robot.telemetry.addData(moduleSide + "Motor 1 Encoder", robot.bulkData1.getMotorCurrentPosition(motor1));
            robot.telemetry.addData(moduleSide + "Motor 2 Encoder", robot.bulkData1.getMotorCurrentPosition(motor2));
            robot.telemetry.addData(moduleSide + "Angle Orient ", rawAngleORIENTAngle);
            robot.telemetry.addData(moduleSide + "Raw Angle Orient ", rawAngleORIENT);
            double rawAngle = (double)(robot.bulkData1.getMotorCurrentPosition(motor2) + robot.bulkData1.getMotorCurrentPosition(motor1))/2.0 * DEGREES_PER_TICK; //motor2-motor1 makes ccw positive (?)
            robot.telemetry.addData(moduleSide + "Raw Angle  ", rawAngle);
            Angle rawAngleAngle = new Angle(rawAngle, Angle.AngleType.ZERO_TO_360_HEADING);
            robot.telemetry.addData(moduleSide + "Raw Angle Angle ", rawAngleAngle);
            return new Angle(rawAngleORIENT, Angle.AngleType.ZERO_TO_360_HEADING);

        }

    //TRACKING METHODS
    //used for robot position tracking

    //new position tracking
    public Vector2d updatePositionTracking (Telemetry telemetry) {
        double newM1Encoder = robot.bulkData1.getMotorCurrentPosition(motor1);
        double newM2Encoder = robot.bulkData1.getMotorCurrentPosition(motor2);
        double newOrientEncoder = robot.bulkData2.getMotorCurrentPosition(encoderOrientation);

        //angles are in radians
        //Angle startingAngleObj = new Angle((lastM1Encoder + lastM2Encoder)/2.0 * DEGREES_PER_TICK, Angle.AngleType.ZERO_TO_360_HEADING);
        //Angle finalAngleObj = new Angle((newM1Encoder + newM2Encoder)/2.0 * DEGREES_PER_TICK, Angle.AngleType.ZERO_TO_360_HEADING);

        Angle startingAngleObj = new Angle(lastorientEncoder* DEGREES_PER_TICK_ORIENT, Angle.AngleType.ZERO_TO_360_HEADING);
        Angle finalAngleObj = new Angle(newOrientEncoder* DEGREES_PER_TICK_ORIENT, Angle.AngleType.ZERO_TO_360_HEADING);

        double averageAngle = Math.toRadians(Angle.getAverageAngle(startingAngleObj, finalAngleObj).getAngle(Angle.AngleType.ZERO_TO_360_CARTESIAN)); //was 180 heading

        double startingPosition = (lastM1Encoder - lastM2Encoder)/2.0  * CM_PER_TICK;
        double finalPosition = (newM1Encoder - newM2Encoder)/2.0 * CM_PER_TICK;
        positionChange = finalPosition - startingPosition;
        //if (reversed) positionChange *= -1; //todo: test this change (including how it may affect heading tracking)

        Vector2d displacementVec;
        double deltaYPos = Math.sin(averageAngle) * positionChange; //was x
        double deltaXPos = Math.cos(averageAngle) * positionChange; //was y
        displacementVec = new Vector2d(-deltaXPos, -deltaYPos); //added negatives based on testing results

        if (debuggingMode) {
            telemetry.addData("Position change: ", positionChange);
            telemetry.addData("Average angle: ", averageAngle);
            telemetry.addData(moduleSide + " Displacement vector: ", displacementVec);
            telemetry.addData(moduleSide + " Delta X Pos: ", displacementVec.getX());
            telemetry.addData(moduleSide + " Delta Y Pos: ", displacementVec.getY()); //was printing the final position instead...
        }

        lastM1Encoder = newM1Encoder;
        lastM2Encoder = newM2Encoder;

        return displacementVec;
    }


    public void resetDistanceTraveled () {
        distanceTraveled = 0;
        lastMotor2Encoder = motor2.getCurrentPosition();
    }
    //returns distance (in cm) traveled since distance was last reset
    public double getDistanceTraveled () {
        return distanceTraveled;
    }
}