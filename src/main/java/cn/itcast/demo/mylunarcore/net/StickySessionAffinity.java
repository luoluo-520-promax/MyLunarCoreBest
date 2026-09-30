package cn.itcast.demo.mylunarcore.net;

/**
 * 基于 UID 哈希的粘性会话：同一玩家高价值操作（抽卡/购买）路由到固定节点下标。
 * 与网关 {@code UserStickyLoadBalancer} 语义对齐，从架构层减少跨节点钱包并发。
 */
public final class StickySessionAffinity {

    private StickySessionAffinity() {
    }

    /** @return [0, nodeCount) 的稳定下标 */
    public static int nodeIndexForUid(int uid, int nodeCount) {
        if (nodeCount <= 0) {
            return 0;
        }
        return Math.floorMod(uid, nodeCount);
    }

    /** 当前节点是否为该 UID 的亲和节点。 */
    public static boolean isStickyOwner(int uid, int localNodeIndex, int nodeCount) {
        if (nodeCount <= 1) {
            return true;
        }
        return nodeIndexForUid(uid, nodeCount) == localNodeIndex;
    }
}
