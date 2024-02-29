package rs117.hd.test.utils;

import net.runelite.api.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import rs117.hd.utils.ModelHash;

import static org.junit.Assert.assertEquals;

@RunWith(MockitoJUnitRunner.class)
public class ModelHashTest {
    @Mock
    private Client client;

    @Test
    public void testModelHashPackingAndParsing() {
		int id = 44630;
		long uuid = 0x15cac8000L;
		long hash = 0x15cac9ab7L;
		assertEquals(id, ModelHash.getIdOrIndex(uuid));
		assertEquals(uuid, ModelHash.packUuid(ModelHash.TYPE_OBJECT, id));
		assertEquals(id, ModelHash.getIdOrIndex(uuid));
		assertEquals(ModelHash.TYPE_OBJECT, ModelHash.getType(uuid));
		assertEquals(uuid, ModelHash.generateUuid(client, hash, null));
		assertEquals(id, ModelHash.getIdOrIndex(ModelHash.generateUuid(client, hash, null)));
	}
}
