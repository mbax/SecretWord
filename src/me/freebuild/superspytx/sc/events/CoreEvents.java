package me.freebuild.superspytx.sc.events;

import me.freebuild.superspytx.sc.SecretWord;
import me.freebuild.superspytx.sc.database.SecretPlayer;
import me.freebuild.superspytx.sc.settings.Configuration;
import me.freebuild.superspytx.sc.settings.Permissions;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerLoginEvent.Result;
import org.bukkit.event.player.PlayerQuitEvent;

public class CoreEvents implements Listener
{

    public SecretWord core = null;

    public CoreEvents(SecretWord instance)
    {
        core = instance;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void reallyLogin(PlayerLoginEvent e)
    {
        Configuration.log("Player logging in: " + e.getPlayer().getName());
        // logged in from another location check.
        if (Bukkit.getPlayerExact(e.getPlayer().getName()) != null && Configuration.blockLoginFromOtherLocation)
        {
            Player pl = Bukkit.getPlayerExact(e.getPlayer().getName());
            pl.sendMessage(Configuration.prefix + ChatColor.DARK_RED + "Somebody tried to login to this server on your account!" + (Bukkit.getOnlineMode() ? " Please go change your minecraft password ASAP!" : ""));
            e.disallow(Result.KICK_OTHER, "You are already logged ingame (maybe wait a minute?)");
            Configuration.log("Player kicked because he's already logged in.");
            core.getDB().failedlogins++;
            return;
        }

        // now register the player instance.
        SecretPlayer player = new SecretPlayer(core, e.getPlayer().getName(), e.getAddress().toString().split(":")[0].replace("/", ""));
        core.getDB().secplayers.put(player.getName(), player);
        player.setBukkitPlayer(e.getPlayer());

        // check permissions.
        if (Configuration.enableByPermission && !Permissions.LOGIN.check(e.getPlayer()))
        {
            player.setRegistered(true);
            player.setLoggedIn(true);
            Configuration.log("Player doesn't require to use SecretWord!");
            return;
        }

        // check registered
        player.setRegistered(core.getDB().userExists(player.getName()));

        // crap, this got removed
        if (core.getServer().getOnlineMode() && Configuration.onlineModeBehavior)
        {
            Configuration.log("Checking IP match..");
            player.setLoggedIn(core.getDB().ipMatches(player.getIP(), player.getName()));
            Configuration.log("IP match? " + Boolean.toString(player.isLoggedIn()));
        }

        // then check if they have to relogin due to half hour stuff.
        if (Permissions.HALFHOUR.check(e.getPlayer()))
        {
            Configuration.log("Checking Halfhour..");
            player.setLoggedIn(core.getDB().halfHourCheck(player.getName()));
            Configuration.log("Been less than half hour? " + Boolean.toString(player.isLoggedIn()));
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onchat(PlayerChatEvent event)
    {
        if (core.getDB().secplayers.containsKey(event.getPlayer().getName()))
        {
            SecretPlayer player = core.getDB().secplayers.get(event.getPlayer().getName());
            String word = event.getMessage();
            if (player.isLoggedIn() && player.isRegistered())
            {
                return;
            }

            if (player.threeSecondRule())
            {
                event.setCancelled(true);
                return;
            }

            if (!player.isLoggedIn() && !player.isRegistered())
            {
                // register the player.
                // but first, get the word in our string.
                if (word.length() < Configuration.minwordlength)
                {
                    player.getPlayer().sendMessage(Configuration.prefix + ChatColor.RED + "Your secret word must be longer than " + Integer.toString(Configuration.minwordlength) + " characters.");
                    event.setCancelled(true);
                    return;
                }

                // good, register it and login.
                core.getDB().addLogin(player.getName(), word);
                player.setRegistered(true);
                player.setLoggedIn(true);
                event.setCancelled(true);
            }
            else if (!player.isLoggedIn())
            {
                // process login

                if (!core.getDB().checkLogin(player.getName(), word))
                {
                    player.getPlayer().sendMessage(Configuration.prefix + ChatColor.RED + "Your secret word is incorrect!");
                    if (player.triggerAttempt())
                    {
                        core.getDB().secplayers.remove(player.getName());
                        core.getDB().failedlogins++;
                    }

                    event.setCancelled(true);
                    return;
                }

                player.setLoggedIn(true);
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onjoin(PlayerJoinEvent e)
    {
        if (core.getDB().secplayers.containsKey(e.getPlayer().getName()))
        {
            SecretPlayer player = core.getDB().secplayers.get(e.getPlayer().getName());
            player.setInitialLocation(e.getPlayer().getLocation());
            if (!player.isLoggedIn())
            {
                // nullify the join message if necessary
                if (Configuration.hideJoinNotifications && !Permissions.SHOWJOIN.check(e.getPlayer()))
                {
                    player.setJoinMessage(e.getJoinMessage());
                    e.setJoinMessage(null);
                }

                // check creative mode/strip it if necessary.
                player.setHasCreative(e.getPlayer().getGameMode() == GameMode.CREATIVE);

                // clear inventory
                player.setInventory(e.getPlayer().getInventory().getContents());
                e.getPlayer().getInventory().clear();

                // now lets check if registered or just stupid.
                if (!player.isRegistered())
                {
                    player.getPlayer().sendMessage(Configuration.prefix + ChatColor.YELLOW + "You must register a secret word! Type the secret word you want before continuing.");
                }
                else
                {
                    player.getPlayer().sendMessage(Configuration.prefix + ChatColor.RED + "Please enter your secret word.");
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void unregister(PlayerQuitEvent e)
    {
        SecretPlayer player = core.getDB().secplayers.get(e.getPlayer().getName());

        if (player != null)
        {
            if (!player.isLoggedIn())
            {
                try
                {
                    e.getPlayer().getInventory().addItem(player.getInventory());
                }
                catch (Exception bukkitderpsalot)
                {
                }
            }
        }

        core.getDB().secplayers.remove(e.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void unregister(PlayerKickEvent e)
    {
        if (e.isCancelled())
            return;

        SecretPlayer player = core.getDB().secplayers.get(e.getPlayer().getName());

        if (player != null)
        {
            if (!player.isLoggedIn())
            {
                try
                {
                    e.getPlayer().getInventory().addItem(player.getInventory());
                }
                catch (Exception bukkitderpsalot)
                {
                }
            }
        }

        core.getDB().secplayers.remove(e.getPlayer().getName());
    }

}
