package rs117.hd.utils;

import com.google.common.annotations.VisibleForTesting;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.HashSet;

@Slf4j
public class GsonUtils {
    @VisibleForTesting
    public static boolean THROW_WHEN_PARSING_FAILS = false;

    public static HashSet<Integer> parseIDArray(JsonReader in, @Nullable Class<?> idContainer) throws IOException
    {
        HashSet<Integer> ids = new HashSet<>();
        in.beginArray();
        while (in.hasNext())
        {
            switch (in.peek())
            {
                case NUMBER:
                    try
                    {
                        ids.add(in.nextInt());
                    }
                    catch (NumberFormatException ex)
                    {
                        String message = "Failed to parse int";
                        if (THROW_WHEN_PARSING_FAILS)
                        {
                            throw new RuntimeException(message, ex);
                        }
                        log.error(message, ex);
                    }
                    break;
                case STRING:
                    String fieldName = in.nextString();
                    if (idContainer == null)
                    {
                        String message = String.format("String '%s' is not supported by this parser", fieldName);
                        if (THROW_WHEN_PARSING_FAILS)
                        {
                            throw new RuntimeException(message);
                        }
                        log.error(message);
                        continue;
                    }

                    try
                    {
                        Field field = idContainer.getField(fieldName);
                        if (!field.getType().equals(int.class))
                        {
                            String message = String.format("Field '%s' in %s is not an int", fieldName, idContainer.getName());
                            if (THROW_WHEN_PARSING_FAILS)
                            {
                                throw new RuntimeException(message);
                            }
                            log.error(message);
                            continue;
                        }
                        ids.add(field.getInt(null));
                    }
                    catch (NoSuchFieldException ex)
                    {
                        String message = String.format("Missing key '%s' in %s", fieldName, idContainer.getName());
                        if (THROW_WHEN_PARSING_FAILS)
                        {
                            throw new RuntimeException(message, ex);
                        }
                        log.error(message, ex);
                    }
                    catch (IllegalAccessException ex)
                    {
                        String message = String.format("Unable to access field '%s' in %s", fieldName, idContainer.getName());
                        if (THROW_WHEN_PARSING_FAILS)
                        {
                            throw new RuntimeException(message, ex);
                        }
                        log.error(message, ex);
                    }

                    break;
            }
        }
        in.endArray();
        return ids;
    }

    public static void writeIDArray(JsonWriter out, HashSet<Integer> listToWrite, @Nullable Class<?> idContainer) throws IOException
    {
        if (listToWrite.size() == 0)
        {
            out.nullValue();
            return;
        }

        if (idContainer == null)
        {
            out.beginArray();
            for (int i : listToWrite)
            {
                out.value(i);
            }
            out.endArray();
            return;
        }

        HashMap<Integer, String> idNames = new HashMap<>();
        for (Field field : idContainer.getFields())
        {
            if (field.getType().equals(int.class))
            {
                try
                {
                    int value = field.getInt(null);
                    idNames.put(value, field.getName());
                }
                catch (IllegalAccessException ignored) {}
            }
        }

        out.beginArray();
        for (int id : listToWrite)
        {
            String name = idNames.get(id);
            if (name == null)
            {
                out.value(id);
            }
            else
            {
                out.value(name);
            }
        }
        out.endArray();
    }
}
