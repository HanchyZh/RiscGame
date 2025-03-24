package risc;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * Minimal RISC Server, allowing 2~5 players.
 * Listens for new client connections, starts the game when the specified number of players have joined.
 */
public class RiscServer {
    // 允许的最小玩家数和最大玩家数
    public static final int MIN_PLAYERS = 2;
    public static final int MAX_PLAYERS = 5;

    // 服务器监听端口（可根据需要修改）
    public static final int PORT = 12345;

    private final List<ClientHandler> clientHandlers;
    private final Game game;

    // 本局实际需要多少玩家（由管理员在服务器启动时指定）
    private final int desiredPlayers;

    /**
     * 构造函数，传入本局期望的玩家数量
     */
    public RiscServer(int desiredPlayers) {
        this.clientHandlers = new ArrayList<>();
        this.game = new Game();  // 这里的 Game() 内部可能会调用更复杂的地图配置，如 setupLargerMap()
        this.game.setUpMap(desiredPlayers);
        this.desiredPlayers = desiredPlayers;
    }

    /**
     * main 方法：启动服务器前，先让用户在控制台输入需要多少玩家
     */
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        int numPlayers = 0;
        // 循环直到输入合法的玩家数量（2～5）
        while (numPlayers < MIN_PLAYERS || numPlayers > MAX_PLAYERS) {
            System.out.println("请输入本局游戏的玩家数量 (" + MIN_PLAYERS + "~" + MAX_PLAYERS + ")：");
            String line = sc.nextLine().trim();
            try {
                numPlayers = Integer.parseInt(line);
            } catch (NumberFormatException e) {
                // 如果输入无法转为数字，就重置为 0 并继续
                numPlayers = 0;
            }
        }
        sc.close();

        // 创建并启动服务器
        RiscServer server = new RiscServer(numPlayers);
        server.runServer();
    }

    /**
     * 主体逻辑：等待玩家连接，直到达成 desiredPlayers 人数，然后启动游戏流程
     */
    private void runServer() {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("服务器已启动，端口 " + PORT);
            System.out.println("本局需要 " + this.desiredPlayers + " 位玩家连接才能开始游戏。");

            while (clientHandlers.size() < desiredPlayers) {
                System.out.println("等待玩家连接中...");
                Socket socket = serverSocket.accept();
                System.out.println("玩家已连接: " + socket.getInetAddress());

                ClientHandler handler = new ClientHandler(socket, this, clientHandlers.size());
                clientHandlers.add(handler);
                handler.start();

                // 达到指定玩家数量后退出等待循环
                if (clientHandlers.size() == desiredPlayers) {
                    System.out.println("达到指定玩家人数 " + desiredPlayers + "，开始游戏。");
                    break;
                }
            }
            // 开始游戏
            startGame();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 一旦所有玩家都连接，初始化游戏并开始循环处理
     */
    private void startGame() {
        // 初始化游戏，注册玩家
        game.initPlayers(clientHandlers.size());

        // 初始阶段：给每个玩家分配初始单位
        broadcastMessage("所有玩家开始放置初始兵力。每人有 " + game.getInitialUnits() + " 点可分配。\n");
        gamePhaseInitialPlacement();

        // 进入主要游戏循环
        while (!game.hasWinner()) {
            broadcastMessage("\n=== 新回合开始 ===\n");

            issueOrdersPhase();

            executeOrdersPhase();
            // 检查是否有人被淘汰或者出现赢家
            game.updatePlayerStatus();

            // 向出局玩家发送淘汰信息
            for (ClientHandler ch : clientHandlers) {
                if (!game.getPlayer(ch.getPlayerID()).isAlive()) {
                    ch.sendMessage("你已被淘汰！");
                }
            }

            if (game.hasWinner()) {
                broadcastMessage("游戏结束！获胜者是：" + game.getWinner().getName() + "\n");
                break;
            }
        }

        // 可选：结束后做一些清理操作
        broadcastMessage("游戏已结束。\n");
        closeAllConnections();
    }

    /**
     * 初始分配兵力阶段（本示例里是自动分配，也可扩展为让玩家自己分配）
     */
    /**
     * 初始兵力分配阶段：让每位玩家在自己领土上手动分配初始单位。
     */
    private void gamePhaseInitialPlacement() {
        broadcastMessage("初始兵力分配阶段：每位玩家有 " + game.getInitialUnits() + " 个单位，请根据你的领土数量自行分配。");
        List<Thread> threads = new ArrayList<>();

        // 为每个存活玩家启动一个线程进行初始兵力分配
        for (ClientHandler ch : clientHandlers) {
            if (!game.getPlayer(ch.getPlayerID()).isAlive()) {
                continue;
            }
            Thread t = new Thread(() -> {
                ch.sendMessage("请为你的各个领土分配初始兵力。");
                ch.collectInitialPlacement(game);
            });
            threads.add(t);
            t.start();
        }
        // 等待所有玩家完成初始兵力分配
        for (Thread t : threads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        broadcastMessage("初始兵力分配完成。\n当前地图状态：\n" + game.getMapState());
    }


    /**
     * 命令下达阶段：让每位玩家输入 (M)ove, (A)ttack, (D)one
     * 通过为每个玩家启动独立线程，实现并发输入
     */
    private void issueOrdersPhase() {
        broadcastMessage("请输入指令：(M)ove, (A)ttack, (D)one。\n");
        List<Thread> threads = new ArrayList<>();
        // 为每个存活玩家启动一个线程去收集命令
        for (ClientHandler ch : clientHandlers) {
            if (!game.getPlayer(ch.getPlayerID()).isAlive()) {
                continue;
            }
            Thread t = new Thread(() -> {
                ch.sendMessage("轮到你下达命令，请依次输入。\n");
                ch.collectOrders(game);
            });
            threads.add(t);
            t.start();
        }
        // 等待所有玩家的命令收集完成
        for (Thread t : threads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 执行命令阶段：先执行移动，再执行攻击
     */
    private void executeOrdersPhase() {
        // 先执行移动命令
        broadcastMessage("\n执行移动命令...\n");
        game.executeAllMoveOrders();

        // 再执行攻击命令
        broadcastMessage("\n执行攻击命令...\n");
        game.executeAllAttackOrders();

        // 清空本回合的所有命令
        game.clearAllOrders();

        // 回合结束：给每个领地增加 1 个单位
        game.addOneUnitToEachTerritory();

        // 回合结束后打印地图信息
        broadcastMessage("本回合结束后的地图：\n" + game.getMapState());
    }

    /**
     * 广播消息给所有玩家
     */
    public void broadcastMessage(String msg) {
        for (ClientHandler ch : clientHandlers) {
            ch.sendMessage(msg);
        }
        // 同时在服务器端输出日志
        System.out.println("Broadcast: " + msg);
    }

    /**
     * 关闭与所有玩家的连接
     */
    public void closeAllConnections() {
        for (ClientHandler ch : clientHandlers) {
            try {
                ch.sendMessage("连接即将关闭...\n");
                ch.closeConnection();
            } catch (Exception e) {
                // 忽略异常
            }
        }
    }

    /**
     * 向外暴露当前游戏对象
     */
    public Game getGame() {
        return this.game;
    }
}