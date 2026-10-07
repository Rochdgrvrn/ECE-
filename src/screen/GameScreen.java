package screen;

import java.awt.event.KeyEvent;
import java.util.HashSet;
import java.util.Set;

import engine.CoinDropManager;
import engine.Cooldown;
import engine.Core;
import engine.CurrencyManager;
import engine.GameSettings;
import engine.GameState;
import engine.Achievement;
import entity.Bullet;
import entity.BulletPool;
import entity.Coin;
import entity.CoinPool;
import entity.EnemyShip;
import entity.EnemyShipFormation;
import entity.Entity;
import entity.Ship;

/**
 * Implements the game screen, where the action happens.
 * 
 * @author <a href="mailto:RobertoIA1987@gmail.com">Roberto Izquierdo Amo</a>
 * 
 */
public class GameScreen extends Screen {

	/** Milliseconds until the screen accepts user input. */
	private static final int INPUT_DELAY = 6000;
	/** Bonus score for each life remaining at the end of the level. */
	private static final int LIFE_SCORE = 100;
	/** Minimum time between bonus ship's appearances. */
	private static final int BONUS_SHIP_INTERVAL = 20000;
	/** Maximum variance in the time between bonus ship's appearances. */
	private static final int BONUS_SHIP_VARIANCE = 10000;
	/** Time until bonus ship explosion disappears. */
	private static final int BONUS_SHIP_EXPLOSION = 500;
	/** Time from finishing the level to screen change. */
	private static final int SCREEN_CHANGE_INTERVAL = 1500;
	/** How long an achievement unlock popup remains visible. */
	private static final int ACHIEVEMENT_POPUP_INTERVAL = 3000;
	/** Height of the interface separation line. */
	private static final int SEPARATION_LINE_HEIGHT = 40;
	/** Coins awarded when a regular enemy's drop chance succeeds. */
	private static final int COIN_VALUE = 1;
	/** Coins guaranteed when the special bonus ship is destroyed. */
	private static final int BONUS_COIN_VALUE = 5;
	/** Minimum time between two pause-key toggles, to debounce the key. */
	private static final int PAUSE_KEY_DEBOUNCE = 200;
	/** Minimum time between two pause-menu cursor moves. */
	private static final int PAUSE_SELECTION_DEBOUNCE = 200;

	/**
	 * Options listed in the pause menu, in the order they are drawn.
	 */
	private enum PauseOption {
		/** Closes the pause menu and continues the level. */
		RESUME("Resume"),
		/** Restarts the current level from the beginning. */
		RESTART("Restart"),
		/** Ends the run and returns to the score / main menu flow. */
		QUIT("Quit to menu");

		/** Text drawn for this option. */
		private final String label;

		PauseOption(final String label) {
			this.label = label;
		}

		/**
		 * @return Text drawn for this option.
		 */
		private String getLabel() {
			return this.label;
		}

		/**
		 * @return Option below this one, wrapping around to the top.
		 */
		private PauseOption next() {
			PauseOption[] values = values();
			return values[(this.ordinal() + 1) % values.length];
		}

		/**
		 * @return Option above this one, wrapping around to the bottom.
		 */
		private PauseOption previous() {
			PauseOption[] values = values();
			return values[(this.ordinal() - 1 + values.length) % values.length];
		}
	}

	/** Current game difficulty settings. */
	private GameSettings gameSettings;
	/** Current difficulty level number. */
	private int level;
	/** Formation of enemy ships. */
	private EnemyShipFormation enemyShipFormation;
	/** Player's ship. */
	private Ship ship;
	/** Bonus enemy ship that appears sometimes. */
	private EnemyShip enemyShipSpecial;
	/** Minimum time between bonus ship appearances. */
	private Cooldown enemyShipSpecialCooldown;
	/** Time until bonus ship explosion disappears. */
	private Cooldown enemyShipSpecialExplosionCooldown;
	/** Time from finishing the level to screen change. */
	private Cooldown screenFinishedCooldown;
	/** Time until the achievement unlock popup closes. */
	private Cooldown achievementPopupCooldown;
	/** Achievement currently shown in the unlock popup. */
	private Achievement unlockedAchievement;
	/** Set of all bullets fired by on screen ships. */
	private Set<Bullet> bullets;
	/** Set of coins currently dropped and falling on screen. */
	private Set<Coin> coins;
	/** Decides, with a low configurable chance, whether a kill drops a
	 * coin, so currency income doesn't scale 1:1 with kills. */
	private CoinDropManager coinDropManager;
	/** Current score. */
	private int score;
	/** Player lives left. */
	private int lives;
	/** Total bullets shot by the player. */
	private int bulletsShot;
	/** Total ships destroyed by the player. */
	private int shipsDestroyed;
	/** Moment the game starts. */
	private long gameStartTime;
	/** Checks if the level is finished. */
	private boolean levelFinished;
	/** Checks if a bonus life is received. */
	private boolean bonusLife;
	/** Diamonds earned this run but not yet cashed out; lost on death,
	 * banked into DiamondManager only when the player cashes out. */
	private int pendingDiamonds;
	/** Whether gameplay is currently paused. */
	private boolean paused;
	/** Debounces the pause key so holding it doesn't toggle every frame. */
	private Cooldown pauseKeyCooldown;
	/** Debounces cursor moves in the pause menu. */
	private Cooldown pauseSelectionCooldown;
	/** Option the cursor is on in the pause menu. */
	private PauseOption pauseSelection;
	/** Score the level started with, restored by "Restart". */
	private final int initialScore;
	/** Lives the level started with (bonus life included), restored by
	 * "Restart". */
	private final int initialLives;
	/** Bullets-shot count the level started with, restored by "Restart". */
	private final int initialBulletsShot;
	/** Ships-destroyed count the level started with, restored by
	 * "Restart". */
	private final int initialShipsDestroyed;
	/** Pending diamonds the level started with, restored by "Restart". */
	private final int initialPendingDiamonds;

	/**
	 * Constructor, establishes the properties of the screen.
	 * 
	 * @param gameState
	 *            Current game state.
	 * @param gameSettings
	 *            Current game settings.
	 * @param bonnusLife
	 *            Checks if a bonus life is awarded this level.
	 * @param width
	 *            Screen width.
	 * @param height
	 *            Screen height.
	 * @param fps
	 *            Frames per second, frame rate at which the game is run.
	 */
	public GameScreen(final GameState gameState,
			final GameSettings gameSettings, final boolean bonusLife,
			final int width, final int height, final int fps) {
		super(width, height, fps);

		this.gameSettings = gameSettings;
		this.bonusLife = bonusLife;
		this.level = gameState.getLevel();
		this.score = gameState.getScore();
		this.lives = gameState.getLivesRemaining();
		if (this.bonusLife)
			this.lives++;
		this.bulletsShot = gameState.getBulletsShot();
		this.shipsDestroyed = gameState.getShipsDestroyed();
		this.pendingDiamonds = gameState.getPendingDiamonds();

		// Snapshot of how the level started, so "Restart" has something to
		// come back to.
		this.initialScore = this.score;
		this.initialLives = this.lives;
		this.initialBulletsShot = this.bulletsShot;
		this.initialShipsDestroyed = this.shipsDestroyed;
		this.initialPendingDiamonds = this.pendingDiamonds;
	}

	/**
	 * Initializes basic screen properties, and adds necessary elements.
	 */
	public final void initialize() {
		super.initialize();

		enemyShipFormation = new EnemyShipFormation(this.gameSettings);
		enemyShipFormation.attach(this);
		this.ship = new Ship(this.width / 2, this.height - 30);
		// Appears each 10-30 seconds.
		this.enemyShipSpecialCooldown = Core.getVariableCooldown(
				BONUS_SHIP_INTERVAL, BONUS_SHIP_VARIANCE);
		this.enemyShipSpecialCooldown.reset();
		this.enemyShipSpecialExplosionCooldown = Core
				.getCooldown(BONUS_SHIP_EXPLOSION);
		this.screenFinishedCooldown = Core.getCooldown(SCREEN_CHANGE_INTERVAL);
		this.achievementPopupCooldown = Core.getCooldown(
				ACHIEVEMENT_POPUP_INTERVAL);
		this.bullets = new HashSet<Bullet>();
		this.coins = new HashSet<Coin>();
		this.coinDropManager = new CoinDropManager();
		this.pauseKeyCooldown = Core.getCooldown(PAUSE_KEY_DEBOUNCE);
		this.pauseSelectionCooldown = Core.getCooldown(PAUSE_SELECTION_DEBOUNCE);
		this.pauseSelection = PauseOption.RESUME;

		// Special input delay / countdown.
		this.gameStartTime = System.currentTimeMillis();
		this.inputDelay = Core.getCooldown(INPUT_DELAY);
		this.inputDelay.reset();
	}

	/**
	 * Starts the action.
	 * 
	 * @return Next screen code.
	 */
	public final int run() {
		super.run();

		this.score += LIFE_SCORE * (this.lives - 1);
		this.logger.info("Screen cleared with a score of " + this.score);

		return this.returnCode;
	}

	/**
	 * Updates the elements on screen and checks for events.
	 */
	protected final void update() {
		super.update();

		if (inputManager.isKeyDown(KeyEvent.VK_P)
				&& this.pauseKeyCooldown.checkFinished()) {
			this.paused = !this.paused;
			this.pauseKeyCooldown.reset();
			this.pauseSelection = PauseOption.RESUME;
			this.logger.info(this.paused ? "Game paused" : "Game resumed");
		}

		if (this.paused) {
			updatePauseMenu();
			draw();
			return;
		}

		if (this.inputDelay.checkFinished() && !this.levelFinished) {

			if (!this.ship.isDestroyed()) {
				boolean moveRight = inputManager.isKeyDown(KeyEvent.VK_RIGHT)
						|| inputManager.isKeyDown(KeyEvent.VK_D);
				boolean moveLeft = inputManager.isKeyDown(KeyEvent.VK_LEFT)
						|| inputManager.isKeyDown(KeyEvent.VK_A);

				boolean isRightBorder = this.ship.getPositionX()
						+ this.ship.getWidth() + this.ship.getSpeed() > this.width - 1;
				boolean isLeftBorder = this.ship.getPositionX()
						- this.ship.getSpeed() < 1;

				if (moveRight && !isRightBorder) {
					this.ship.moveRight();
				}
				if (moveLeft && !isLeftBorder) {
					this.ship.moveLeft();
				}
				if (inputManager.isKeyDown(KeyEvent.VK_SPACE))
					if (this.ship.shoot(this.bullets))
						this.bulletsShot++;
			}

			if (this.enemyShipSpecial != null) {
				if (!this.enemyShipSpecial.isDestroyed())
					this.enemyShipSpecial.move(2, 0);
				else if (this.enemyShipSpecialExplosionCooldown.checkFinished())
					this.enemyShipSpecial = null;

			}
			if (this.enemyShipSpecial == null
					&& this.enemyShipSpecialCooldown.checkFinished()) {
				this.enemyShipSpecial = new EnemyShip();
				this.enemyShipSpecialCooldown.reset();
				this.logger.info("A special ship appears");
			}
			if (this.enemyShipSpecial != null
					&& this.enemyShipSpecial.getPositionX() > this.width) {
				this.enemyShipSpecial = null;
				this.logger.info("The special ship has escaped");
			}

			this.ship.update();
			this.enemyShipFormation.update();
			this.enemyShipFormation.shoot(this.bullets);
		}

		manageCollisions();
		cleanBullets();
		updateCoins();
		draw();

		if ((this.enemyShipFormation.isEmpty() || this.lives == 0)
				&& !this.levelFinished) {
			this.levelFinished = true;
			this.screenFinishedCooldown.reset();

			// Level cleared alive: level N is worth N diamonds, kept pending
			// until cashed out (see engine.DiamondManager), and coins still
			// falling are collected so the last kills' drops aren't lost.
			if (this.enemyShipFormation.isEmpty() && this.lives > 0) {
				this.pendingDiamonds += this.level;
				collectRemainingCoins();
			}
		}

		if (this.levelFinished && this.screenFinishedCooldown.checkFinished())
			this.isRunning = false;

	}

	/**
	 * Handles cursor movement and selection while the pause menu is open.
	 */
	private void updatePauseMenu() {
		if (!this.pauseSelectionCooldown.checkFinished())
			return;

		if (inputManager.isKeyDown(KeyEvent.VK_UP)
				|| inputManager.isKeyDown(KeyEvent.VK_W)) {
			this.pauseSelection = this.pauseSelection.previous();
			this.pauseSelectionCooldown.reset();
		} else if (inputManager.isKeyDown(KeyEvent.VK_DOWN)
				|| inputManager.isKeyDown(KeyEvent.VK_S)) {
			this.pauseSelection = this.pauseSelection.next();
			this.pauseSelectionCooldown.reset();
		} else if (inputManager.isKeyDown(KeyEvent.VK_SPACE)) {
			this.pauseSelectionCooldown.reset();
			confirmPauseSelection();
		}
	}

	/**
	 * Carries out whichever pause menu option the cursor was on.
	 */
	private void confirmPauseSelection() {
		switch (this.pauseSelection) {
			case RESUME:
				this.paused = false;
				break;
			case RESTART:
				restartLevel();
				break;
			case QUIT:
				// No dedicated "abandon" path exists in the screen flow,
				// but ending the run the same way running out of lives
				// does takes us straight to the existing score screen,
				// which already offers "back to menu" / "play again".
				this.lives = 0;
				this.paused = false;
				break;
			default:
				break;
		}
	}

	/**
	 * Restarts the current level: score, lives, bullets shot, ships
	 * destroyed and pending diamonds go back to what they were when the
	 * level began, and every on-screen element is rebuilt from scratch.
	 */
	private void restartLevel() {
		this.score = this.initialScore;
		this.lives = this.initialLives;
		this.bulletsShot = this.initialBulletsShot;
		this.shipsDestroyed = this.initialShipsDestroyed;
		this.pendingDiamonds = this.initialPendingDiamonds;
		this.levelFinished = false;
		initialize();
		this.paused = false;
	}

	/**
	 * Draws the elements associated with the screen.
	 */
	private void draw() {
		drawManager.initDrawing(this);

		if (!this.paused) {
			// Ship, enemies, bullets and falling coins are hidden while
			// paused, so the pause menu isn't cluttered by the frozen
			// battlefield behind it - only the top HUD stays visible.
			drawManager.drawEntity(this.ship, this.ship.getPositionX(),
					this.ship.getPositionY());
			if (this.enemyShipSpecial != null)
				drawManager.drawEntity(this.enemyShipSpecial,
						this.enemyShipSpecial.getPositionX(),
						this.enemyShipSpecial.getPositionY());

			enemyShipFormation.draw();

			for (Bullet bullet : this.bullets)
				drawManager.drawEntity(bullet, bullet.getPositionX(),
						bullet.getPositionY());

			for (Coin coin : this.coins)
				drawManager.drawCoin(coin, coin.getPositionX(),
						coin.getPositionY());
		}

		// Interface.
		drawManager.drawScore(this, this.score);
		drawManager.drawLives(this, this.lives);
		drawManager.drawCoinBalance(this, CurrencyManager.getInstance()
				.getCoins(), 36);
		drawManager.drawDiamonds(this, engine.DiamondManager.getInstance()
				.getDiamonds());
		drawManager.drawHorizontalLine(this, SEPARATION_LINE_HEIGHT - 1);
		if (this.unlockedAchievement != null) {
			drawManager.drawAchievementUnlocked(this, this.unlockedAchievement);
			if (this.achievementPopupCooldown.checkFinished())
				this.unlockedAchievement = null;
		}

		if (this.paused) {
			String[] options = { PauseOption.RESUME.getLabel(),
					PauseOption.RESTART.getLabel(),
					PauseOption.QUIT.getLabel() };
			drawManager.drawPauseMenu(this, options,
					this.pauseSelection.ordinal());
			drawManager.drawKeyHints(this,
					"w+s / arrows to move, space to select");
		}

		// Countdown to game start (skipped while paused, so the pause menu
		// stays visible instead of being painted over).
		if (!this.paused && !this.inputDelay.checkFinished()) {
			int countdown = (int) ((INPUT_DELAY
					- (System.currentTimeMillis()
							- this.gameStartTime)) / 1000);
			drawManager.drawCountDown(this, this.level, countdown,
					this.bonusLife);
			drawManager.drawHorizontalLine(this, this.height / 2 - this.height
					/ 12);
			drawManager.drawHorizontalLine(this, this.height / 2 + this.height
					/ 12);
		}

		drawManager.completeDrawing(this);
	}

	/**
	 * Cleans bullets that go off screen.
	 */
	private void cleanBullets() {
		Set<Bullet> recyclable = new HashSet<Bullet>();
		for (Bullet bullet : this.bullets) {
			bullet.update();
			if (bullet.getPositionY() < SEPARATION_LINE_HEIGHT
					|| bullet.getPositionY() > this.height)
				recyclable.add(bullet);
		}
		this.bullets.removeAll(recyclable);
		BulletPool.recycle(recyclable);
	}

	/**
	 * Manages collisions between bullets and ships.
	 */
	private void manageCollisions() {
		Set<Bullet> recyclable = new HashSet<Bullet>();
		for (Bullet bullet : this.bullets)
			if (bullet.getSpeed() > 0) {
				if (checkCollision(bullet, this.ship) && !this.levelFinished) {
					recyclable.add(bullet);
					if (!this.ship.isDestroyed()) {
						this.ship.destroy();
						this.lives--;
						this.logger.info("Hit on player ship, " + this.lives
								+ " lives remaining.");
					}
				}
			} else {
				for (EnemyShip enemyShip : this.enemyShipFormation)
					if (!enemyShip.isDestroyed()
							&& checkCollision(bullet, enemyShip)) {
						this.score += enemyShip.getPointValue();
						this.shipsDestroyed++;
						this.enemyShipFormation.destroy(enemyShip);
						maybeDropCoin(enemyShip);
						showUnlockedAchievement(Core.getAchievementManager()
								.recordEnemyDefeated());
						recyclable.add(bullet);
					}
				if (this.enemyShipSpecial != null
						&& !this.enemyShipSpecial.isDestroyed()
						&& checkCollision(bullet, this.enemyShipSpecial)) {
					this.score += this.enemyShipSpecial.getPointValue();
					this.shipsDestroyed++;
					this.enemyShipSpecial.destroy();
					dropCoin(this.enemyShipSpecial, BONUS_COIN_VALUE);
					showUnlockedAchievement(Core.getAchievementManager()
							.recordEnemyDefeated());
					this.enemyShipSpecialExplosionCooldown.reset();
					recyclable.add(bullet);
				}
			}
		this.bullets.removeAll(recyclable);
		BulletPool.recycle(recyclable);
	}

	/**
	 * Rolls the coin-drop chance for a just-destroyed regular enemy and, if
	 * it succeeds, drops a coin at its position. A coin does not drop on
	 * every kill on purpose: see {@link CoinDropManager} for why.
	 *
	 * @param destroyedEnemy
	 *            Enemy ship that was just destroyed.
	 */
	private void maybeDropCoin(final EnemyShip destroyedEnemy) {
		if (this.coinDropManager.rollForDrop())
			dropCoin(destroyedEnemy, COIN_VALUE);
	}

	/**
	 * Drops a coin worth the given value from the center of a destroyed
	 * enemy. Used directly for the special ship, which always pays out.
	 *
	 * @param destroyedEnemy
	 *            Enemy ship that was just destroyed.
	 * @param value
	 *            Coins awarded when the coin is collected.
	 */
	private void dropCoin(final EnemyShip destroyedEnemy, final int value) {
		this.coins.add(CoinPool.getCoin(
				destroyedEnemy.getPositionX() + destroyedEnemy.getWidth() / 2,
				destroyedEnemy.getPositionY() + destroyedEnemy.getHeight() / 2,
				value));
	}

	/**
	 * Moves falling coins, hands coins touched by the ship to the
	 * CurrencyManager, and recycles coins that leave the screen.
	 */
	private void updateCoins() {
		Set<Coin> recyclable = new HashSet<Coin>();
		for (Coin coin : this.coins) {
			coin.update();
			if (this.lives > 0 && !this.ship.isDestroyed()
					&& checkCollision(coin, this.ship)) {
				CurrencyManager.getInstance().addCoins(coin.getValue());
				recyclable.add(coin);
				this.logger.info("Coin collected, balance: "
						+ CurrencyManager.getInstance().getCoins());
			} else if (coin.getPositionY() > this.height) {
				recyclable.add(coin);
			}
		}
		this.coins.removeAll(recyclable);
		CoinPool.recycle(recyclable);
	}

	/**
	 * Collects every coin still on screen at once, used when the level is
	 * cleared so drops from the last enemies aren't lost.
	 */
	private void collectRemainingCoins() {
		if (this.coins.isEmpty())
			return;
		int total = 0;
		for (Coin coin : this.coins)
			total += coin.getValue();
		CurrencyManager.getInstance().addCoins(total);
		this.logger.info("Level cleared, collected " + total
				+ " remaining coins, balance: "
				+ CurrencyManager.getInstance().getCoins());
		CoinPool.recycle(this.coins);
		this.coins.clear();
	}

	/**
	 * Displays a popup when an enemy defeat unlocks an achievement.
	 *
	 * @param achievement Newly unlocked achievement, if any.
	 */
	private void showUnlockedAchievement(final Achievement achievement) {
		if (achievement != null) {
			this.unlockedAchievement = achievement;
			this.achievementPopupCooldown.reset();
		}
	}

	/**
	 * Checks if two entities are colliding.
	 * 
	 * @param a
	 *            First entity, the bullet.
	 * @param b
	 *            Second entity, the ship.
	 * @return Result of the collision test.
	 */
	private boolean checkCollision(final Entity a, final Entity b) {
		// Calculate center point of the entities in both axis.
		int centerAX = a.getPositionX() + a.getWidth() / 2;
		int centerAY = a.getPositionY() + a.getHeight() / 2;
		int centerBX = b.getPositionX() + b.getWidth() / 2;
		int centerBY = b.getPositionY() + b.getHeight() / 2;
		// Calculate maximum distance without collision.
		int maxDistanceX = a.getWidth() / 2 + b.getWidth() / 2;
		int maxDistanceY = a.getHeight() / 2 + b.getHeight() / 2;
		// Calculates distance.
		int distanceX = Math.abs(centerAX - centerBX);
		int distanceY = Math.abs(centerAY - centerBY);

		return distanceX < maxDistanceX && distanceY < maxDistanceY;
	}

	/**
	 * Returns a GameState object representing the status of the game.
	 * 
	 * @return Current game state.
	 */
	public final GameState getGameState() {
		return new GameState(this.level, this.score, this.lives,
				this.bulletsShot, this.shipsDestroyed, this.pendingDiamonds);
	}
}